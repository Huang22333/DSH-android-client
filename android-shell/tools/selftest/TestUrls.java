package app.dsh.shell;

/** Urls 的自测：javac Urls.java TestUrls.java && java TestUrls */
public final class TestUrls {

    private static int fails = 0;
    private static int total = 0;
    private static boolean quiet = false;

    private static void ok(String what, boolean cond) {
        total++;
        if (cond) {
            if (!quiet) {
                System.out.println("ok   " + what);
            }
        } else {
            fails++;
            System.out.println((quiet ? "     （自检反例，预期失败）" : "FAIL ") + what);
        }
    }

    /**
     * 断言必须真的能失败：故意传一个必然不成立的条件，确认计数会红。
     * 起因是这里曾有过的假绿 —— 一条断言漏写 `!`，永远通过，测试全绿却什么都没验。
     */
    private static void assertCheckWorks() {
        int beforeFails = fails;
        int beforeTotal = total;
        quiet = true;
        ok("反例", false);
        quiet = false;
        if (fails != beforeFails + 1) {
            System.out.println("FAIL 断言计数没生效，测试框架本身有问题");
            System.exit(2);
        }
        fails = beforeFails;                 // 撤回故意失败
        total = beforeTotal;
        System.out.println("ok   测试框架自检（假绿防护）");
    }

    private static void norm(String in, String expect) {
        String got = Urls.normalize(in);
        ok("normalize(" + in + ") = " + got + (expect == null ? " (null)" : ""),
                expect == null ? got == null : expect.equals(got));
    }

    public static void main(String[] args) {
        assertCheckWorks();
        norm("https://dsh.example.com:443", "https://dsh.example.com:443/");
        norm("  dsh.example.com:443  ", "https://dsh.example.com:443/");
        norm("https://a.com/x", "https://a.com/x/");
        norm("http://10.0.0.1:3081/", "http://10.0.0.1:3081/");
        norm("", null);
        norm("   ", null);
        norm(null, null);
        norm("ftp://a.com", null);
        norm("https://a.com b.com", null);

        // 同站判定：兄弟子域要留在应用内（DSH 同域服务就是 dsh-file.*）
        ok("dsh.example.com ~ dsh-file.example.com",
                Urls.sameSite("dsh.example.com", "dsh-file.example.com"));
        ok("dsh.example.com ~ example.com", Urls.sameSite("dsh.example.com", "example.com"));
        // 兄弟子域按末两段比较 ⇒ mcsm 也算同站。这是刻意的：整台机器都是自己的部署，
        // 面板/同域服务/DSH 之间互相跳转都应留在应用内。（曾把这条断言写成不带 ! 的
        // Urls.sameSite(...)，测试恒绿，属于假绿 —— 保留此注释警示。）
        ok("dsh.example.com ~ mcsm.example.com",
                Urls.sameSite("dsh.example.com", "mcsm.example.com"));
        ok("a.com !~ b.com", !Urls.sameSite("a.com", "b.com"));
        ok("a.com !~ aa.com", !Urls.sameSite("a.com", "aa.com"));
        ok("dsh.example.com !~ evil.com", !Urls.sameSite("dsh.example.com", "evil.com"));
        ok("dsh.example.com !~ example.com.evil.com",
                !Urls.sameSite("dsh.example.com", "example.com.evil.com"));
        ok("10.0.0.1 == 10.0.0.1",
                Urls.sameSite("10.0.0.1", "10.0.0.1"));
        ok("10.0.0.1 !~ 10.0.0.2",
                !Urls.sameSite("10.0.0.1", "10.0.0.2"));
        ok("IP 不与域名混判",
                !Urls.sameSite("10.0.0.1", "dsh.example.com"));
        ok("localhost != dsh.example.com",
                !Urls.sameSite("localhost", "dsh.example.com"));

        System.out.println(fails == 0 ? "ALL PASS" : (fails + " FAILED"));
        if (fails > 0) {
            System.exit(1);
        }
    }
}
