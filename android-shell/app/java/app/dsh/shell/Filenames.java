package app.dsh.shell;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 从 Content-Disposition / URL 推断下载文件名（纯 Java，可脱机自测）。
 *
 * 为什么不能直接用 android.webkit.URLUtil.guessFileName：
 * 它只认 `filename=`，**不认 RFC 5987 的 `filename*=utf-8''...`**。
 * 而 同域服务 恰好只发后者（实测所有响应都是
 * `attachment; filename*=utf-8''<百分号编码>`，Content-Type 恒为
 * application/octet-stream），于是 guessFileName 解析不到文件名，退化成按 MIME
 * 猜测扩展名 —— octet-stream 正好映射到 .bin，**所有文件都存成 .bin**。
 */
public final class Filenames {

    private Filenames() {}

    private static final Map<String, String> MIME_EXT = new HashMap<String, String>();

    static {
        MIME_EXT.put("application/pdf", ".pdf");
        MIME_EXT.put("application/zip", ".zip");
        MIME_EXT.put("application/gzip", ".gz");
        MIME_EXT.put("application/x-tar", ".tar");
        MIME_EXT.put("application/json", ".json");
        MIME_EXT.put("application/xml", ".xml");
        MIME_EXT.put("application/vnd.android.package-archive", ".apk");
        MIME_EXT.put("application/msword", ".doc");
        MIME_EXT.put("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                ".docx");
        MIME_EXT.put("application/vnd.ms-excel", ".xls");
        MIME_EXT.put("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                ".xlsx");
        MIME_EXT.put("application/vnd.ms-powerpoint", ".ppt");
        MIME_EXT.put("text/plain", ".txt");
        MIME_EXT.put("text/html", ".html");
        MIME_EXT.put("text/css", ".css");
        MIME_EXT.put("text/csv", ".csv");
        MIME_EXT.put("text/markdown", ".md");
        MIME_EXT.put("image/jpeg", ".jpg");
        MIME_EXT.put("image/png", ".png");
        MIME_EXT.put("image/gif", ".gif");
        MIME_EXT.put("image/webp", ".webp");
        MIME_EXT.put("image/svg+xml", ".svg");
        MIME_EXT.put("audio/mpeg", ".mp3");
        MIME_EXT.put("audio/ogg", ".ogg");
        MIME_EXT.put("video/mp4", ".mp4");
        MIME_EXT.put("video/webm", ".webm");
    }

    /** MIME 对应的扩展名；未知返回 null（**不要**返回 .bin，那正是要修的症状）。 */
    public static String extForMime(String mime) {
        if (mime == null) {
            return null;
        }
        int semi = mime.indexOf(';');
        String m = (semi >= 0 ? mime.substring(0, semi) : mime).trim().toLowerCase(Locale.ROOT);
        return MIME_EXT.get(m);
    }

    /**
     * 解析 Content-Disposition 里的文件名，优先 RFC 5987 的 filename*（RFC 6266 规定它
     * 优先于 filename）。拿不到返回 null。
     */
    public static String fromDisposition(String cd) {
        if (cd == null || cd.isEmpty()) {
            return null;
        }
        String starred = param(cd, "filename*");
        if (starred != null) {
            String decoded = decodeExtValue(starred);
            if (decoded != null && !decoded.isEmpty()) {
                return decoded;
            }
        }
        String plain = param(cd, "filename");
        if (plain != null) {
            String v = unquote(plain);
            if (!v.isEmpty()) {
                return v;
            }
        }
        return null;
    }

    /**
     * 取 `key=` 的参数值。不能用简单的 indexOf 切逗号：文件名里可能带逗号/分号
     * （在引号内），而且 filename* 的值本身含 `'`。
     */
    private static String param(String header, String key) {
        String lower = header.toLowerCase(Locale.ROOT);
        int i = 0;
        while (true) {
            int at = lower.indexOf(key, i);
            if (at < 0) {
                return null;
            }
            // 必须是独立的参数名（前面是 ; 或开头，后面紧跟 =）
            boolean leftOk = at == 0 || header.charAt(at - 1) == ';'
                    || Character.isWhitespace(header.charAt(at - 1));
            int eq = at + key.length();
            while (eq < header.length() && Character.isWhitespace(header.charAt(eq))) {
                eq++;
            }
            if (leftOk && eq < header.length() && header.charAt(eq) == '=') {
                int start = eq + 1;
                while (start < header.length() && Character.isWhitespace(header.charAt(start))) {
                    start++;
                }
                if (start < header.length() && header.charAt(start) == '"') {
                    int end = header.indexOf('"', start + 1);
                    return end < 0 ? header.substring(start + 1) : header.substring(start + 1, end);
                }
                int end = start;
                while (end < header.length() && header.charAt(end) != ';') {
                    end++;
                }
                return header.substring(start, end).trim();
            }
            i = at + key.length();
        }
    }

    private static String unquote(String v) {
        String s = v.trim();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            s = s.substring(1, s.length() - 1);
        }
        return s;
    }

    /**
     * RFC 5987 ext-value：`charset'language'percent-encoded`。
     * 带单引号才按该格式解，否则当普通值（含百分号解码）。
     */
    static String decodeExtValue(String v) {
        String s = v;
        Charset cs = Charset.forName("UTF-8");
        int q1 = s.indexOf('\'');
        if (q1 >= 0) {
            int q2 = s.indexOf('\'', q1 + 1);
            if (q2 > q1) {
                String charset = s.substring(0, q1).trim();
                try {
                    cs = Charset.forName(charset);
                } catch (Exception ignored) {
                    cs = Charset.forName("UTF-8");
                }
                s = s.substring(q2 + 1);
            }
        }
        return percentDecode(s, cs);
    }

    /** 百分号解码；`+` 保持不变（这里是文件名/路径，不是表单编码）。 */
    static String percentDecode(String s, Charset cs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = hex(s.charAt(i + 1));
                int lo = hex(s.charAt(i + 2));
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            byte[] b = String.valueOf(c).getBytes(cs);
            out.write(b, 0, b.length);
        }
        return new String(out.toByteArray(), cs);
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    /** 从 URL 末段取文件名（解码百分号、去掉查询串）。拿不到返回 null。 */
    public static String fromUrl(String url) {
        if (url == null) {
            return null;
        }
        String u = url;
        int q = u.indexOf('?');
        if (q >= 0) {
            u = u.substring(0, q);
        }
        int h = u.indexOf('#');
        if (h >= 0) {
            u = u.substring(0, h);
        }
        int slash = u.lastIndexOf('/');
        String last = slash >= 0 ? u.substring(slash + 1) : u;
        if (last.isEmpty()) {
            return null;
        }
        return percentDecode(last, Charset.forName("UTF-8"));
    }

    /** 去掉路径分隔符与控制字符等，避免写文件失败或目录穿越。 */
    public static String sanitize(String name) {
        if (name == null) {
            return null;
        }
        String s = name.replace('\\', '/');
        // 先按路径切段，丢掉 "." 与 ".." 段本身（只把分隔符换成下划线的话，
        // "../../etc/passwd" 会变成 "_.._etc_passwd"，点段还在，仍然难看且危险）
        StringBuilder joined = new StringBuilder();
        for (String seg : s.split("/")) {
            String t = seg.trim();
            if (t.isEmpty() || ".".equals(t) || "..".equals(t)) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append('_');
            }
            joined.append(t);
        }
        s = joined.toString();
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c < 0x20 || c == 0x7F ? '_' : c);
        }
        s = sb.toString();
        while (s.startsWith(".")) {           // 别写出隐藏文件
            s = s.substring(1);
        }
        s = s.trim();
        if (s.isEmpty()) {
            return null;
        }
        return s.length() > 180 ? s.substring(s.length() - 180) : s;
    }

    /**
     * 综合推断：Content-Disposition → URL 末段 → 按 MIME 拼个兜底名。
     * 永远返回一个可用的文件名。
     */
    public static String resolve(String disposition, String url, String mime) {
        String name = sanitize(fromDisposition(disposition));
        if (name != null) {
            return name;
        }
        name = sanitize(fromUrl(url));
        if (name != null) {
            return name;
        }
        String ext = extForMime(mime);
        return "download" + (ext == null ? "" : ext);
    }
}
