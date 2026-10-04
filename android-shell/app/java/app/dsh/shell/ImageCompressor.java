package app.dsh.shell;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * 上传前的图片压缩：把图缩到 DSH 会「原样放行」的尺寸，顺便去掉 EXIF。
 *
 * 为什么值得做（实测依据）：
 *   手机相机原图 4080×3060 = 12.5MP、3~6MB ⇒ 移动网上传要好几秒；
 *   到服务端后又被 sharp 重编码一遍（这台 VM 配置很低）。
 *   而 DSH 的合规阈值是 w*h ≤ 4,194,304 且 ≤1MB 且无 EXIF —— 只要预先满足，
 *   服务端会**直接放行、一次重编码都不跑**（canPassThroughNormalization）。
 *   更关键：模型最终只看到约 823×823 等效，所以压到 2048 级（总像素 ≤ 2048×2048）
 *   **不损失任何模型可见细节**。
 *
 * 几条硬要求：
 *   · **绝不改变宽高比**（几何由 ImageSizing 负责，已单测各极端比例）
 *   · **不放大**小图；已合规的小图也可能被重编码（为了去掉 EXIF），但尺寸不变
 *   · 12MP 直接解码要 48MB 内存 ⇒ 必须两段式解码 + inSampleSize 降采样
 *   · PNG（截图常见）不能盲转 JPEG —— 文字会出现压缩噪点；优先保 PNG
 *   · 任何一步失败都回退原图，**绝不能因此让上传失败**
 */
public final class ImageCompressor {

    private static final String TAG = "DSH/img";
    public static final String DIR = "upload";

    /**
     * JPEG 质量阶梯。刻意取和 DSH 一样的 {85,75,60}：三档间隔拉开，
     * 每档都能换来真实体积下降，避免「试一堆档位只省几 KB」白烧 CPU。
     * 实测真实照片在 2048 级（总像素 ≤ 2048×2048）下 q85 就已 ~400-500KB，第一档通常就命中。
     */
    private static final int[] QUALITY_LADDER = {85, 75, 60};
    /** 质量阶梯仍超限时的尺寸阶梯（长边按比例缩）。 */
    private static final double[] SIZE_LADDER = {0.85, 0.72, 0.6};
    /**
     * 尺寸阶梯内使用的精简质量档：只为「尽快摸到 ≤1MB」，
     * 不再把完整质量阶梯重跑一遍（否则最坏情况要编码 20 次）。
     */
    private static final int[] QUALITY_LADDER_SHRUNK = {85, 70};
    /** trim 的宽限期：这段时间内动过的文件不删（WebView 可能还在读）。 */
    public static final long TRIM_GRACE_MS = 2 * 60 * 1000L;

    private ImageCompressor() {}

    /** 压缩结果。 */
    public static final class Result {
        /** 可直接交给 WebView 的 Uri（失败时是原 Uri）。 */
        public final Uri uri;
        /** 是否真的产出了压缩文件。 */
        public final boolean compressed;
        /** 原始尺寸 → 压缩后尺寸，用于日志/提示。 */
        public final String detail;

        Result(Uri uri, boolean compressed, String detail) {
            this.uri = uri;
            this.compressed = compressed;
            this.detail = detail;
        }
    }

    /** 入口：压缩一个图片 Uri。永远返回可用的 Uri（最差是原样返回）。 */
    public static Result compress(Context ctx, Uri src) {
        if (ctx == null || src == null) {
            return new Result(src, false, "无效输入");
        }
        String mime = resolveMime(ctx, src);
        if (mime == null || !mime.startsWith("image/")) {
            return new Result(src, false, "非图片，跳过");
        }
        if ("image/gif".equals(mime)) {
            // 动图不碰：重编码会丢帧，且 DSH 对 gif 本来就不走放行分支
            return new Result(src, false, "GIF 跳过");
        }

        try {
            // ── 第一段：只读头部，拿原始尺寸（不解码像素，几乎不耗内存）──
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            try (InputStream in = open(ctx, src)) {
                if (in == null) {
                    return new Result(src, false, "打不开");
                }
                BitmapFactory.decodeStream(in, null, probe);
            }
            int srcW = probe.outWidth;
            int srcH = probe.outHeight;
            if (srcW <= 0 || srcH <= 0) {
                return new Result(src, false, "不是有效图片");
            }

            boolean png = "image/png".equals(mime) || "image/webp".equals(mime);
            ImageSizing.Size target = ImageSizing.fit(srcW, srcH);

            // ── 第二段：按需降采样解码，避免大图 OOM ──
            Bitmap decoded = decodeScaled(ctx, src, target.width, target.height);
            if (decoded == null) {
                return new Result(src, false, "解码失败");
            }

            Bitmap out = decoded;
            try {
                // createScaledBitmap 只有在尺寸不同时才有意义
                if (decoded.getWidth() != target.width || decoded.getHeight() != target.height) {
                    Bitmap scaled = Bitmap.createScaledBitmap(
                            decoded, target.width, target.height, true);
                    if (scaled != decoded) {
                        out = scaled;
                    }
                }

                File dir = new File(ctx.getCacheDir(), DIR);
                if (!dir.exists() && !dir.mkdirs()) {
                    return new Result(src, false, "无法建目录");
                }

                // ── 编码：先按质量阶梯，再按尺寸阶梯，直到 ≤1MB ──
                Encoded encoded = encodeWithinLimit(out, png, dir);
                if (encoded == null) {
                    return new Result(src, false, "编码失败");
                }
                Uri uri = Uri.parse("content://" + ctx.getPackageName()
                        + CaptureProvider.AUTHORITY_SUFFIX + "/" + encoded.file.getName());
                String detail = srcW + "x" + srcH + " → " + encoded.width + "x" + encoded.height
                        + " (" + (encoded.bytes / 1024) + "KB)";
                return new Result(uri, true, detail);
            } finally {
                if (out != decoded) {
                    out.recycle();
                }
                decoded.recycle();
            }
        } catch (OutOfMemoryError oom) {
            Log.w(TAG, "压缩时内存不足，回退原图", oom);
            return new Result(src, false, "内存不足，用原图");
        } catch (Exception e) {
            Log.w(TAG, "压缩失败，回退原图", e);
            return new Result(src, false, "出错，用原图");
        }
    }

    /** 已编码结果。 */
    private static final class Encoded {
        final File file;
        final int width;
        final int height;
        final int bytes;

        Encoded(File f, int w, int h, int b) {
            this.file = f;
            this.width = w;
            this.height = h;
            this.bytes = b;
        }
    }

    /**
     * 按「质量阶梯 → 尺寸阶梯」找第一个 ≤ MAX_BYTES 的编码结果。
     * PNG 优先保留（截图文字清晰）；PNG 压不下去时才转 JPEG。
     *
     * ⚠️ 每次试探都会落一个临时文件。全部试完后**必须只留下胜出的那个**，
     * 否则一次上传就会在 cache 里攒下好几个几 MB 的废文件（曾这样设计过）。
     */
    private static Encoded encodeWithinLimit(Bitmap src, boolean preferPng, File dir) {
        java.util.List<File> tried = new java.util.ArrayList<>();
        try {
            Encoded best = searchEncoded(src, preferPng, dir, tried);
            if (best == null) {
                return null;
            }
            // 删掉所有落选文件，只留胜出的
            for (File f : tried) {
                if (!f.equals(best.file)) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            return best;
        } catch (RuntimeException | Error e) {
            for (File f : tried) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            throw e;
        }
    }

    /** 实际的阶梯搜索；每产出一个候选就记进 tried。 */
    private static Encoded searchEncoded(Bitmap src, boolean preferPng, File dir,
                                         java.util.List<File> tried) {
        Encoded last = null;

        // PNG：先试原尺寸（保文字），过大再缩尺寸
        if (preferPng) {
            Encoded e = track(tryPng(src, dir), tried);
            if (e != null) {
                last = e;
                if (e.bytes <= ImageSizing.MAX_BYTES) {
                    return e;
                }
            }
            for (double f : SIZE_LADDER) {
                int w = Math.max(1, (int) Math.round(src.getWidth() * f));
                int h = Math.max(1, (int) Math.round(src.getHeight() * f));
                Bitmap s = Bitmap.createScaledBitmap(src, w, h, true);
                try {
                    Encoded e2 = track(tryPng(s, dir), tried);
                    if (e2 != null) {
                        last = e2;
                        if (e2.bytes <= ImageSizing.MAX_BYTES) {
                            return e2;
                        }
                    }
                } finally {
                    if (s != src) {
                        s.recycle();
                    }
                }
            }
            // PNG 实在压不下去 → 落到下面的 JPEG 路径
        }

        // JPEG：质量阶梯（先原地降质量，尽量保住分辨率）
        for (int q : QUALITY_LADDER) {
            Encoded e = track(tryJpeg(src, q, dir), tried);
            if (e != null) {
                last = e;
                if (e.bytes <= ImageSizing.MAX_BYTES) {
                    return e;
                }
            }
        }
        // 仍超限 → 缩尺寸，每档只试两档质量（高频噪声类照片会走到这里）
        for (double f : SIZE_LADDER) {
            int w = Math.max(1, (int) Math.round(src.getWidth() * f));
            int h = Math.max(1, (int) Math.round(src.getHeight() * f));
            Bitmap s = Bitmap.createScaledBitmap(src, w, h, true);
            try {
                for (int q : QUALITY_LADDER_SHRUNK) {
                    Encoded e = track(tryJpeg(s, q, dir), tried);
                    if (e != null) {
                        last = e;
                        if (e.bytes <= ImageSizing.MAX_BYTES) {
                            return e;
                        }
                    }
                }
            } finally {
                if (s != src) {
                    s.recycle();
                }
            }
        }
        // 全部超限：返回最小的那个（宁可略大，也不失败）
        return last;
    }

    private static Encoded track(Encoded e, java.util.List<File> tried) {
        if (e != null) {
            tried.add(e.file);
        }
        return e;
    }

    private static Encoded tryPng(Bitmap b, File dir) {
        return write(b, Bitmap.CompressFormat.PNG, 100, ".png", dir);
    }

    private static Encoded tryJpeg(Bitmap b, int q, File dir) {
        return write(b, Bitmap.CompressFormat.JPEG, q, ".jpg", dir);
    }

    /** 落盘一个新文件；失败返回 null。 */
    private static Encoded write(Bitmap b, Bitmap.CompressFormat fmt, int q,
                                 String ext, File dir) {
        File f = new File(dir, "dsh-" + System.currentTimeMillis() + "-"
                + Integer.toHexString(b.hashCode()) + ext);
        try (OutputStream os = new FileOutputStream(f)) {
            if (!b.compress(fmt, q, os)) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                return null;
            }
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return null;
        }
        int bytes = (int) Math.min(Integer.MAX_VALUE, f.length());
        return new Encoded(f, b.getWidth(), b.getHeight(), bytes);
    }

    /**
     * 解码到「刚好不小于目标尺寸」的位图。
     * 两段式 + inSampleSize：12MP 原图若直接解码要 48MB，降采样后只要几 MB。
     */
    private static Bitmap decodeScaled(Context ctx, Uri src, int wantW, int wantH) {
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        try (InputStream in = open(ctx, src)) {
            if (in == null) {
                return null;
            }
            BitmapFactory.decodeStream(in, null, probe);
        } catch (Exception e) {
            return null;
        }
        if (probe.outWidth <= 0 || probe.outHeight <= 0) {
            return null;
        }

        int sample = 1;
        // 取 2 的幂，使解码结果仍 ≥ 目标（宁大勿小，后面再精确缩放）
        while (probe.outWidth / (sample * 2) >= wantW
                && probe.outHeight / (sample * 2) >= wantH) {
            sample *= 2;
        }
        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inSampleSize = sample;
        opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream in = open(ctx, src)) {
            if (in == null) {
                return null;
            }
            return BitmapFactory.decodeStream(in, null, opt);
        } catch (OutOfMemoryError oom) {
            // 再退一档重试一次，仍失败就交给上层回退
            opt.inSampleSize = sample * 2;
            try (InputStream in = open(ctx, src)) {
                return in == null ? null : BitmapFactory.decodeStream(in, null, opt);
            } catch (Throwable t) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static InputStream open(Context ctx, Uri uri) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            return cr.openInputStream(uri);
        } catch (Exception e) {
            return null;
        }
    }

    /** 取 MIME；ContentResolver 给不出时按扩展名兜底。 */
    static String resolveMime(Context ctx, Uri uri) {
        try {
            String m = ctx.getContentResolver().getType(uri);
            if (m != null && !m.isEmpty()) {
                return m.toLowerCase(Locale.ROOT);
            }
        } catch (Exception ignored) {
            // 落到扩展名判断
        }
        String path = uri.getLastPathSegment();
        if (path == null) {
            return null;
        }
        int dot = path.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return m == null ? null : m.toLowerCase(Locale.ROOT);
    }

    /**
     * 清掉旧的压缩产物，只留最近 keep 个。
     *
     * ⚠️ 绝不能删「刚产出的」文件：WebView 拿到 Uri 后是**异步**读取内容的，
     * 若紧接着就删掉，用户会看到「选了图却传不上去」（多选时尤其明显）。
     * 所以额外跳过最近 {@link #TRIM_GRACE_MS} 内动过的文件。
     */
    public static void trim(Context ctx, int keep) {
        try {
            File[] files = new File(ctx.getCacheDir(), DIR).listFiles();
            if (files == null || files.length <= keep) {
                return;
            }
            java.util.Arrays.sort(files,
                    (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            long now = System.currentTimeMillis();
            int kept = 0;
            for (File f : files) {
                if (now - f.lastModified() < TRIM_GRACE_MS) {
                    continue;               // 刚写的，WebView 可能还在读
                }
                kept++;
                if (kept <= keep) {
                    continue;
                }
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (Exception ignored) {
            // 清理失败不影响上传
        }
    }
}
