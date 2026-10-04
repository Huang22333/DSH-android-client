package app.dsh.shell;

/**
 * ImageSizing 自测。重点是**宽高比不许变形**，覆盖各种真实比例：
 * 手机竖拍 3:4、横拍 4:3、全景 3:1 / 5:1、超长截图 1:10、方形、小图。
 *
 * 跑法：java -cp <classes> app.dsh.shell.TestImageSizing
 */
public final class TestImageSizing {

    private static int fails = 0;
    private static int total = 0;

    private static void ok(String what, boolean cond) {
        total++;
        if (cond) {
            System.out.println("ok   " + what);
        } else {
            fails++;
            System.out.println("FAIL " + what);
        }
    }

    /** 宽高比误差（相对），浮点比较用。 */
    private static double aspectErr(int w0, int h0, int w1, int h1) {
        double a0 = (double) w0 / h0;
        double a1 = (double) w1 / h1;
        return Math.abs(a1 - a0) / a0;
    }

    private static void check(String name, int w, int h) {
        ImageSizing.Size s = ImageSizing.fit(w, h);
        long px = (long) s.width * s.height;
        boolean within = px <= ImageSizing.MAX_PIXELS
                && Math.max(s.width, s.height) <= ImageSizing.MAX_DIMENSION;
        // 极端瘦长图取整后比例误差会略大，1% 以内可接受
        boolean shape = aspectErr(w, h, s.width, s.height) < 0.01;
        boolean notUpscaled = s.width <= w && s.height <= h;
        boolean positive = s.width >= 1 && s.height >= 1;
        ok(String.format("%-46s %5dx%-6d -> %5dx%-6d  px=%8d 比例误差=%.4f%%",
                name, w, h, s.width, s.height, px,
                aspectErr(w, h, s.width, s.height) * 100),
                within && shape && notUpscaled && positive);
    }

    public static void main(String[] args) {
        System.out.println("预算: px<=" + ImageSizing.MAX_PIXELS
                + " 单边<=" + ImageSizing.MAX_DIMENSION + "\n");

        // ── 真实机型/场景 ────────────────────────────────────────────
        check("小米15 相机竖拍 4080x3060 (旋转后 3060x4080)", 3060, 4080);
        check("小米15 相机横拍 4080x3060", 4080, 3060);
        check("4:3 常见 4000x3000", 4000, 3000);
        check("画幅 3:2 6000x4000", 6000, 4000);
        check("16:9 全景 3840x2160", 3840, 2160);
        check("方形 3000x3000", 3000, 3000);
        check("竖幅 9:16 2160x3840", 2160, 3840);

        // ── 极端宽高比（最容易压变形/压塌）────────────────────────────
        check("超宽全景 3:1", 9000, 3000);
        check("超宽全景 5:1", 10000, 2000);
        check("超宽全景 10:1", 20000, 2000);
        check("超长截图 1:10", 1080, 10800);
        check("超长截图 1:20", 1080, 21600);
        check("细长条 1:50", 400, 20000);
        check("细长条 50:1", 20000, 400);
        check("1 像素高全景", 6000, 1);
        check("1 像素宽长图", 1, 6000);

        // ── 已合规：不许动、不许放大 ────────────────────────────────
        check("恰好卡上限 2048x2048", 2048, 2048);
        check("上限内 1536x2048", 1536, 2048);
        check("小图 256x256", 256, 256);
        check("极小图 1x1", 1, 1);
        check("单边超 8192 但像素合规 9000x400", 9000, 400);

        // ── 边界算术 ────────────────────────────────────────────────
        ImageSizing.Size exact = ImageSizing.fit(2048, 2048);
        ok("2048x2048 原样返回", exact.width == 2048 && exact.height == 2048);
        ImageSizing.Size tiny = ImageSizing.fit(100, 100);
        ok("小图不被放大", tiny.width == 100 && tiny.height == 100);

        // 结果必须严格落在预算内（用一批随机比例暴力验证）
        int bad = 0;
        java.util.Random rnd = new java.util.Random(42);
        for (int i = 0; i < 20000; i++) {
            int w = 1 + rnd.nextInt(20000);
            int h = 1 + rnd.nextInt(20000);
            ImageSizing.Size s = ImageSizing.fit(w, h);
            if ((long) s.width * s.height > ImageSizing.MAX_PIXELS
                    || Math.max(s.width, s.height) > ImageSizing.MAX_DIMENSION
                    || s.width > w || s.height > h
                    || s.width < 1 || s.height < 1) {
                if (bad < 3) {
                    System.out.println("  越界样例: " + w + "x" + h + " -> " + s);
                }
                bad++;
            }
        }
        ok("随机 20000 组比例全部落在预算内且不放大（越界 " + bad + " 例）", bad == 0);

        // 随机比例下也不许变形
        int skew = 0;
        rnd = new java.util.Random(7);
        for (int i = 0; i < 5000; i++) {
            int w = 100 + rnd.nextInt(9000);
            int h = 100 + rnd.nextInt(9000);
            ImageSizing.Size s = ImageSizing.fit(w, h);
            if (aspectErr(w, h, s.width, s.height) >= 0.01) {
                skew++;
            }
        }
        ok("随机 5000 组比例形变均 <1%（异常 " + skew + " 例）", skew == 0);

        // ── alreadyFits ────────────────────────────────────────────
        ok("alreadyFits: 2048x2048/1MB 边界为真",
                ImageSizing.alreadyFits(2048, 2048, ImageSizing.MAX_BYTES));
        ok("alreadyFits: 超 1 字节为假",
                !ImageSizing.alreadyFits(2048, 2048, ImageSizing.MAX_BYTES + 1));
        ok("alreadyFits: 像素超 1 为假",
                !ImageSizing.alreadyFits(2049, 2048, 1000));
        ok("alreadyFits: 单边超 8192 为假",
                !ImageSizing.alreadyFits(9000, 400, 1000));

        System.out.println(fails == 0 ? ("ALL PASS (" + total + " 项)")
                : (fails + "/" + total + " FAILED"));
        if (fails > 0) {
            System.exit(1);
        }
    }
}
