package app.dsh.shell;

/** 脱离安卓的纯 Java 自测：javac Css.java TestCss.java && java TestCss */
public final class TestCss {

    private static int fails = 0;

    private static void eq(String css, int expect) {
        int got = Css.parse(css);
        boolean ok = got == expect
                || (expect != -1 && got != -1 && (got & 0xFFFFFF) == (expect & 0xFFFFFF));
        if (!ok) {
            fails++;
            System.out.printf("FAIL %-34s got=%08X expect=%08X%n", css, got, expect);
        } else {
            System.out.printf("ok   %-34s -> %08X%n", css, got);
        }
    }

    public static void main(String[] args) {
        eq("rgb(255, 255, 255)", 0xFFFFFFFF);
        eq("#0a0a0a", 0xFF0A0A0A);
        eq("rgba(0, 0, 0, 0)", -1);
        eq("transparent", -1);
        eq("rgb(11, 18, 32)", 0xFF0B1220);
        eq("rgba(255, 255, 255, 0.5)", 0xFFFFFFFF);
        eq("rgba(0 0 0 / 50%)", 0xFF7F7F7F);   // 与白底合成（±1 属舍入，无所谓）
        eq("rgb(10,10,10)", 0xFF0A0A0A);            // 无空格
        eq("rgb(255 255 255)", 0xFFFFFFFF);          // 现代空格语法
        eq("rgba(0,0,0,0.9)", 0xFF191919);           // 90% 黑 合成到白底
        eq(null, -1);
        eq("", -1);
        eq("var(--bg)", -1);
        eq("rgb(999, 0, 3)", 0xFFFF0003);           // 越界夹紧
        eq("rgb(-5, 0, 3)", -1);                     // 负数非法，按无法解析处理
        System.out.println(fails == 0 ? "ALL PASS" : (fails + " FAILED"));
        if (fails > 0) {
            System.exit(1);
        }
    }
}
