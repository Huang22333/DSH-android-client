package app.dsh.shell;

/**
 * 上传前把图片缩到「DSH 会原样放行」的尺寸（纯 Java，可脱机自测）。
 *
 * 为什么必须自己算：DSH 服务端有个短路 —— 图片若已合规就**原样放行、完全不重编码**
 * （`canPassThroughNormalization`）。合规要同时满足：
 *   · 宽×高 ≤ 4,194,304（= 2048×2048，取等号也放行，已用它自己的判定器实测）
 *   · 单边 ≤ 8192
 *   · 编码后字节 ≤ 1MB（发请求那一侧更严；附件侧是 4MB）
 *   · 不能带 EXIF 等元数据（手机相机默认带，所以必须重绘）
 *
 * 只要有一条不满足，服务端就会拿 sharp 把图重编码一遍（在低配机器上），
 * 而我们上传的还是几 MB 原图 —— 这两段都白干。
 *
 * 本类只负责几何：按比例缩到预算内。**绝不改变宽高比**，也绝不放大小图。
 */
public final class ImageSizing {

    private ImageSizing() {}

    /** DSH 附件归一化的默认像素预算（2048×2048）。 */
    public static final int MAX_PIXELS = 2048 * 2048;
    /** DSH 附件归一化的默认单边上限。 */
    public static final int MAX_DIMENSION = 8192;
    /** 发请求侧的字节预算（pi-ai requestImageMaxBytes）。 */
    public static final int MAX_BYTES = 1024 * 1024;

    /** 缩放结果。 */
    public static final class Size {
        public final int width;
        public final int height;

        Size(int w, int h) {
            this.width = w;
            this.height = h;
        }

        @Override
        public String toString() {
            return width + "x" + height;
        }
    }

    /**
     * 按比例缩到预算内。保留宽高比；小图原样返回（不放大小图）。
     *
     * 对极端宽高比（超长全景图）会先受单边上限约束，再受像素预算约束，
     * 不会出现「一边被压到 0」或「比例被拉变形」。
     */
    public static Size fit(int width, int height) {
        return fit(width, height, MAX_PIXELS, MAX_DIMENSION);
    }

    static Size fit(int width, int height, int maxPixels, int maxDim) {
        if (width <= 0 || height <= 0 || maxPixels <= 0 || maxDim <= 0) {
            return new Size(Math.max(1, width), Math.max(1, height));
        }
        // 三个上限取最严的那个：像素预算、单边上限，以及「不放大」
        double byPixels = Math.sqrt((double) maxPixels / ((double) width * height));
        double byDim = (double) maxDim / Math.max(width, height);
        double scale = Math.min(1.0, Math.min(byPixels, byDim));
        if (scale >= 1.0) {
            return new Size(width, height);          // 已合规，不动它
        }

        // 与 DSH 同样的取整方式：先定长边，另一边按原比例回算，避免比例漂移
        int w;
        int h;
        if (width >= height) {
            w = Math.max(1, (int) Math.floor(width * scale));
            h = Math.max(1, (int) Math.round((double) w * height / width));
        } else {
            h = Math.max(1, (int) Math.floor(height * scale));
            w = Math.max(1, (int) Math.round((double) h * width / height));
        }

        // 取整可能刚好越界一位，往回收（只缩不放，且优先动长边）
        while ((long) w * h > maxPixels || Math.max(w, h) > maxDim) {
            if (width >= height) {
                if (w <= 1) {
                    break;
                }
                w--;
                h = Math.max(1, (int) Math.round((double) w * height / width));
            } else {
                if (h <= 1) {
                    break;
                }
                h--;
                w = Math.max(1, (int) Math.round((double) h * width / height));
            }
        }
        return new Size(w, h);
    }

    /** 是否已经合规（不需要重编码）。注意：EXIF 由调用方另行判断。 */
    public static boolean alreadyFits(int width, int height, int bytes) {
        return width > 0 && height > 0
                && (long) width * height <= MAX_PIXELS
                && Math.max(width, height) <= MAX_DIMENSION
                && bytes <= MAX_BYTES;
    }
}
