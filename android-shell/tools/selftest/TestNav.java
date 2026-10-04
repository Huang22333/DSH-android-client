package app.dsh.shell;

/**
 * 导航去向判定的用例：给定"当前入口地址"和一个被点击的 URL，
 * 期望的处置是「应用内」「系统打开」还是「忽略」。
 *
 * 这段逻辑散在 MainActivity 的 handleUrl / routeNewWindow 里（依赖 Android Uri），
 * 这里用等价的纯 Java 判定把规则钉住。改动那两处逻辑时，本文件必须同步。
 *
 * ⚠️ 不是执行真 Android 代码，是规则对照测试（无设备时的最低护栏）。
 */
public final class TestNav {

    private static int fails = 0;
    private static int total = 0;

    private static final int IN_APP = 0;      // 留在 WebView
    private static final int EXTERNAL = 1;    // 交系统处理
    private static final int IGNORE = 2;      // 什么都不做

    /** 对照 handleUrl()：返回处置方式。 */
    static int route(String current, String url) {
        if (url == null || url.isEmpty()) {
            return IGNORE;                    // handleUrl 返回 false ⇒ WebView 忽略
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            String host = host(current);
            String h = host(url);
            if (h == null || Urls.sameSite(h, host)) {
                return IN_APP;
            }
            return EXTERNAL;
        }
        return EXTERNAL;                      // mailto:/tel:/intent:/自定义 scheme
    }

    /** 极简 host 提取（对照 Uri.parse(url).getHost()）。 */
    static String host(String url) {
        if (url == null) {
            return null;
        }
        int at = url.indexOf("://");
        if (at < 0) {
            return null;
        }
        String rest = url.substring(at + 3);
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#' || c == ':') {
                end = i;
                break;
            }
        }
        String h = rest.substring(0, end);
        return h.isEmpty() ? null : h;
    }

    private static void eq(String url, int expect, String desc) {
        total++;
        int got = route("https://dsh.example.com:443/", url);
        String[] name = {"应用内", "系统打开", "忽略"};
        if (got == expect) {
            System.out.printf("ok   %-46s -> %s（%s）%n", url, name[got], desc);
        } else {
            fails++;
            System.out.printf("FAIL %-46s -> %s，期望 %s（%s）%n",
                    url, name[got], name[expect], desc);
        }
    }

    public static void main(String[] args) {
        // 同站：必须留在应用内（否则点「文件」就被踢出去）
        eq("https://dsh.example.com:443/session/abc", IN_APP, "同域");
        eq("https://dsh-file.example.com:443/", IN_APP, "兄弟子域→同域服务");
        eq("https://example.com/", IN_APP, "裸域");
        eq("https://mcsm.example.com/", IN_APP, "同注册域的另一子域");

        // 异站：交给浏览器
        eq("https://github.com/", EXTERNAL, "外部站点");
        eq("https://evil-example.com/", EXTERNAL, "相似域名");

        // 非 http scheme
        eq("mailto:a@b.com", EXTERNAL, "邮件");
        eq("tel:10086", EXTERNAL, "电话");
        eq("intent://x#Intent;scheme=foo;end", EXTERNAL, "intent 协议");
        eq("weixin://dl/chat", EXTERNAL, "自定义 scheme");

        // 边界
        eq("", IGNORE, "空串");
        eq(null, IGNORE, "null");
        eq("javascript:void(0)", EXTERNAL, "javascript 伪协议");
        eq("about:blank", EXTERNAL, "about:blank");

        System.out.println(fails == 0 ? ("ALL PASS (" + total + " 项)")
                : (fails + "/" + total + " FAILED"));
        if (fails > 0) {
            System.exit(1);
        }
    }
}
