package app.dsh.shell;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** CSS 颜色解析（纯 Java，不依赖 android.*，因此可以脱离安卓单独跑单元测试）。 */
public final class Css {

    private Css() {}

    private static final Pattern RGB = Pattern.compile(
            "rgba?\\(\\s*(\\d+)[,\\s]+(\\d+)[,\\s]+(\\d+)(?:\\s*[,/]\\s*([\\d.]+)\\s*(%)?)?\\s*\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HEX = Pattern.compile("^#([0-9a-f]{6})$", Pattern.CASE_INSENSITIVE);

    /** 解析成 0xAARRGGBB；无法解析返回 -1；近全透明也返回 -1（调用方应保留原底色）。 */
    public static int parse(String css) {
        if (css == null) {
            return -1;
        }
        String s = css.trim();
        if (s.isEmpty() || "transparent".equalsIgnoreCase(s)) {
            return -1;
        }
        Matcher h = HEX.matcher(s);
        if (h.matches()) {
            return (int) (0xFF000000L | Long.parseLong(h.group(1), 16));
        }
        Matcher m = RGB.matcher(s);
        if (m.find()) {
            int r = clamp(Integer.parseInt(m.group(1)));
            int g = clamp(Integer.parseInt(m.group(2)));
            int b = clamp(Integer.parseInt(m.group(3)));
            int a = 255;
            if (m.group(4) != null) {
                double av = Double.parseDouble(m.group(4));
                if (m.group(5) != null) {       // rgba(0 0 0 / 50%)
                    av = av * 255.0 / 100.0;
                } else if (av <= 1.0) {         // rgba(0,0,0,0.5)
                    av = av * 255.0;
                }
                a = (int) Math.round(av);
            }
            if (a < 8) {
                return -1;
            }
            if (a < 255) {                      // 与白底合成，壳里不允许出现透明背景
                double f = a / 255.0;
                r = (int) Math.round(r * f + 255 * (1 - f));
                g = (int) Math.round(g * f + 255 * (1 - f));
                b = (int) Math.round(b * f + 255 * (1 - f));
            }
            return pack(r, g, b);
        }
        return -1;
    }

    static int pack(int r, int g, int b) {
        return 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
