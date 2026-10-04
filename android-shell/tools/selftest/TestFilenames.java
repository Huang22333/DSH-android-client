package app.dsh.shell;

/**
 * Filenames 自测。样本全部来自**实测的真实响应头**（同域服务 2026-09-19）。
 * 这组用例就是为了钉死「所有文件变成 .bin」那个 bug，改动解析器前先跑它。
 */
public final class TestFilenames {

    private static int fails = 0;
    private static int total = 0;
    private static boolean quiet = false;

    private static void eq(String what, Object got, Object expect) {
        total++;
        boolean ok = got == null ? expect == null : got.equals(expect);
        if (ok) {
            if (!quiet) {
                System.out.println("ok   " + what + "  -> " + got);
            }
        } else {
            fails++;
            System.out.println("FAIL " + what + "\n       期望 " + expect + "，实际 " + got);
        }
    }

    private static void assertCheckWorks() {
        int bf = fails;
        int bt = total;
        quiet = true;
        eq("反例", "a", "b");
        quiet = false;
        if (fails != bf + 1) {
            System.out.println("FAIL 断言计数没生效，测试框架本身有问题");
            System.exit(2);
        }
        fails = bf;
        total = bt;
        System.out.println("ok   测试框架自检（假绿防护）");
    }

    private static final String OCTET = "application/octet-stream";

    public static void main(String[] args) {
        assertCheckWorks();

        // ── 真实响应头（curl 实测）──────────────────────────────
        eq("apk",
                Filenames.fromDisposition("attachment; filename*=utf-8''DSH-1.1.apk"),
                "DSH-1.1.apk");
        eq("中文名（百分号编码）",
                Filenames.fromDisposition(
                        "attachment; filename*=utf-8''%E7%A4%BA%E4%BE%8B%E6%96%87%E4%BB%B6.md"),
                "示例文件.md");
        eq("ascii md",
                Filenames.fromDisposition("attachment; filename*=utf-8''README.md"),
                "README.md");

        // ── RFC 6266：filename* 优先于 filename ──────────────────
        eq("filename* 优先",
                Filenames.fromDisposition(
                        "attachment; filename=\"fallback.bin\"; filename*=utf-8''real.zip"),
                "real.zip");
        eq("只有 filename（引号）",
                Filenames.fromDisposition("attachment; filename=\"a b.txt\""),
                "a b.txt");
        eq("只有 filename（无引号）",
                Filenames.fromDisposition("attachment; filename=a.txt"), "a.txt");
        eq("大小写不敏感",
                Filenames.fromDisposition("attachment; FILENAME*=UTF-8''x.PDF"), "x.PDF");
        eq("含语言标签",
                Filenames.fromDisposition("attachment; filename*=utf-8'en'hello.txt"),
                "hello.txt");
        eq("带逗号的名字",
                Filenames.fromDisposition("attachment; filename=\"a,b;c.txt\""), "a,b;c.txt");
        eq("inline 无文件名", Filenames.fromDisposition("inline"), null);
        eq("空头", Filenames.fromDisposition(""), null);
        eq("null 头", Filenames.fromDisposition(null), null);

        // ── 关键回归：绝不能因为 MIME 是 octet-stream 就退化成 .bin ──
        eq("★ 真实场景：apk 直链",
                Filenames.resolve("attachment; filename*=utf-8''DSH-1.1.apk",
                        "https://dsh-file.example.com:443/api/raw/Main/DSH-1.1.apk", OCTET),
                "DSH-1.1.apk");
        eq("★ 真实场景：中文 md",
                Filenames.resolve(
                        "attachment; filename*=utf-8''%E7%A4%BA%E4%BE%8B%E6%96%87%E4%BB%B6.md",
                        "https://x/api/raw/Main/%E6%98%8E%E6%97%A9.md", OCTET),
                "示例文件.md");
        eq("无 disposition 时用 URL 末段（octet-stream 也不给 .bin）",
                Filenames.resolve(null, "https://x/api/raw/Main/dsh-apk/README.md", OCTET),
                "README.md");
        eq("URL 带查询串",
                Filenames.resolve(null, "https://x/f/report.pdf?token=abc", OCTET), "report.pdf");
        eq("URL 百分号编码",
                Filenames.resolve(null, "https://x/a/%E6%B5%8B%E8%AF%95.zip", OCTET),
                "测试.zip");
        eq("什么都没有时按 MIME 兜底", Filenames.resolve(null, "https://x/", "application/pdf"),
                "download.pdf");
        eq("MIME 也未知", Filenames.resolve(null, "https://x/", OCTET), "download");

        // ── MIME 映射 ───────────────────────────────────────────
        eq("mime 带参数", Filenames.extForMime("text/plain; charset=utf-8"), ".txt");
        eq("octet-stream 无扩展名映射", Filenames.extForMime(OCTET), null);
        eq("apk mime", Filenames.extForMime("application/vnd.android.package-archive"), ".apk");

        // ── 文件名清理 ──────────────────────────────────────────
        eq("路径穿越", Filenames.sanitize("../../etc/passwd"), "etc_passwd");
        eq("反斜杠", Filenames.sanitize("a\\b.txt"), "a_b.txt");
        eq("控制字符", Filenames.sanitize("a\u0001b.txt"), "a_b.txt");
        eq("隐藏文件去掉前导点", Filenames.sanitize(".bashrc"), "bashrc");
        eq("全空", Filenames.sanitize("   "), null);
        eq("纯点", Filenames.sanitize("..."), null);

        System.out.println(fails == 0
                ? ("ALL PASS (" + total + " 项)")
                : (fails + "/" + total + " FAILED"));
        if (fails > 0) {
            System.exit(1);
        }
    }
}
