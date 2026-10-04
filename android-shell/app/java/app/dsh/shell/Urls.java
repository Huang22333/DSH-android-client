package app.dsh.shell;

/**
 * 地址与同站判定（纯 Java，可脱机自测）。
 */
public final class Urls {

    private Urls() {}

    /** 规范化用户输入的地址；无法识别返回 null。 */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String u = raw.trim();
        if (u.isEmpty()) {
            return null;
        }
        if (!u.contains("://")) {
            u = "https://" + u;
        }
        String lower = u.toLowerCase();
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
            return null;
        }
        if (u.indexOf(' ', u.indexOf("://")) > 0) {
            return null;                    // 地址里混进了空格，多半是粘错
        }
        if (!u.endsWith("/")) {
            u = u + "/";
        }
        return u;
    }

    /**
     * 是否属于同一个站点（可保留在应用内）。
     *
     * DSH 的同域服务、面板等常常是同一主域的**兄弟子域**（如 dsh.example.com / dsh-file.example.com），
     * 简单比 host 会把它们误判成外部链接丢去浏览器，所以按注册域（末两段）比较。
     * IP 地址必须完全相同才算同站。
     */
    public static boolean sameSite(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.equalsIgnoreCase(b)) {
            return true;
        }
        if (isIp(a) || isIp(b)) {
            return false;
        }
        String[] x = a.toLowerCase().split("\\.");
        String[] y = b.toLowerCase().split("\\.");
        if (x.length < 2 || y.length < 2) {
            return false;
        }
        return x[x.length - 1].equals(y[y.length - 1])
                && x[x.length - 2].equals(y[y.length - 2]);
    }

    static boolean isIp(String host) {
        if (host.indexOf(':') >= 0) {       // IPv6 字面量
            return true;
        }
        String[] p = host.split("\\.");
        if (p.length != 4) {
            return false;
        }
        for (String s : p) {
            if (s.isEmpty() || s.length() > 3) {
                return false;
            }
            for (int i = 0; i < s.length(); i++) {
                if (!Character.isDigit(s.charAt(i))) {
                    return false;
                }
            }
        }
        return true;
    }
}
