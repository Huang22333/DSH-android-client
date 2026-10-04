package app.dsh.shell;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 极简 FileProvider：只服务「拍照」那一个输出文件。
 *
 * 为什么不直接用 androidx.core 的 FileProvider：那会把 60KB 的包撑成几 MB，
 * 而这里只需要暴露一个私有目录下的文件给相机应用写。
 *
 * 安全边界（重要）：只允许访问 `getCacheDir()/capture/` 下的**直接子文件**，
 * 且文件名不含路径分隔符 —— 否则本应用等于对外开了一个任意文件读取口。
 */
public class CaptureProvider extends ContentProvider {

    public static final String AUTHORITY_SUFFIX = ".fileprovider";

    /** 拍照输出目录。 */
    private static final String DIR = "capture";
    /** 压缩产物目录（ImageCompressor 写入）。 */
    private static final String UPLOAD_DIR = "upload";

    @Override
    public boolean onCreate() {
        return true;
    }

    /**
     * 把 Uri 解析成 cache 下的文件。
     *
     * ⚠️ **绝不能要求文件已存在**。相机拍照的流程是：拿到我们给的 Uri →
     * `openOutputStream` → 新建文件并写入。也就是说调用 resolve() 的那一刻
     * 文件**还不存在**。早先这里加过 `f.isFile()` 判断，结果相机写不进去、
     * 拍完等于没拍（真机复现过）。
     *
     * 存在性只在**读**的时候才该关心，由调用方按 mode 决定（见 openFile）。
     */
    private File resolve(Uri uri) {
        if (getContext() == null || uri == null) {
            return null;
        }
        if (!(getContext().getPackageName() + AUTHORITY_SUFFIX).equals(uri.getAuthority())) {
            return null;
        }
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty()) {
            return null;
        }
        // 防目录穿越：只认纯文件名
        if (name.contains("/") || name.contains("\\") || name.contains("..")
                || name.contains(File.separator)) {
            return null;
        }
        // 两个允许的目录：拍照输出、压缩产物。都必须在 cache 下。
        // 优先返回「已存在」的那个；都不存在时返回拍照目录（供写入创建）。
        File captureDir = new File(getContext().getCacheDir(), DIR);
        File uploadDir = new File(getContext().getCacheDir(), UPLOAD_DIR);
        for (File dir : new File[]{uploadDir, captureDir}) {
            File f = new File(dir, name);
            try {
                if (f.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)
                        && f.isFile()) {
                    return f;
                }
            } catch (Exception ignored) {
                // 试下一个目录
            }
        }
        // 都不存在：给拍照目录（写入时会被创建）
        File f = new File(captureDir, name);
        try {
            if (f.getCanonicalPath().startsWith(captureDir.getCanonicalPath() + File.separator)) {
                return f;
            }
        } catch (Exception ignored) {
            // 路径异常，拒绝
        }
        return null;
    }

    /** 是否为写模式（相机新建文件走这条）。 */
    private static boolean isWriteMode(String mode) {
        return mode != null && (mode.contains("w") || mode.contains("rwt"));
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolve(uri);
        if (f == null) {
            throw new FileNotFoundException("拒绝访问：" + uri);
        }
        boolean write = isWriteMode(mode);
        // 读：文件必须存在（否则 WebView 会拿到空内容）
        // 写：文件可以不存在，由 MODE_CREATE 建出来
        if (!write && !f.isFile()) {
            throw new FileNotFoundException("文件不存在：" + uri);
        }
        if (write) {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new FileNotFoundException("无法创建目录：" + parent);
            }
        }
        // 相机需要能写；"r" 只读，其余（w/rw/rwt）可写并在需要时创建。
        // ⚠️ 必须用同一个 isWriteMode() 判定，别在这里再写一遍 "r".equals(mode)：
        // 两处判据不一致会导致「按读处理却按写打开」这类难查的错。
        int flags = write
                ? ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE
                : ParcelFileDescriptor.MODE_READ_ONLY;
        return ParcelFileDescriptor.open(f, flags);
    }

    @Override
    public String getType(Uri uri) {
        String name = uri == null ? null : uri.getLastPathSegment();
        if (name == null) {
            return "image/jpeg";
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return "image/jpeg";
        }
        String ext = name.substring(dot + 1).toLowerCase();
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime == null ? "image/jpeg" : mime;
    }

    /** 让系统（和相机应用）能看到文件名/大小。 */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        File f = resolve(uri);
        if (f == null) {
            return null;
        }
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        MatrixCursor.RowBuilder row = c.newRow();
        for (String col : cols) {
            if (OpenableColumns.DISPLAY_NAME.equals(col)) {
                row.add(f.getName());
            } else if (OpenableColumns.SIZE.equals(col)) {
                row.add(f.exists() ? f.length() : 0);
            } else {
                row.add(null);
            }
        }
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;                       // 只读 provider
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
