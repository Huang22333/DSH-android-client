package app.dsh.shell;

import java.io.ByteArrayOutputStream;

/**
 * 极小 Base64（编解码）。
 *
 * 为什么自己写：{@code java.util.Base64} 是 **API 26+**，而壳 minSdk 24；
 * 低版本上调用直接 NoSuchMethodError。这里 40 行搞定，附纯 Java 自测。
 */
final class B64 {
    private B64() {}

    private static final char[] ENC =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    private static final int[] DEC = new int[128];

    static {
        for (int i = 0; i < 128; i++) DEC[i] = -1;
        for (int i = 0; i < ENC.length; i++) DEC[ENC[i]] = i;
    }

    /** 编码（无换行，适合一行一条的协议）。 */
    static String encode(byte[] in) {
        StringBuilder sb = new StringBuilder((in.length + 2) / 3 * 4);
        int i = 0;
        while (i + 3 <= in.length) {
            int v = ((in[i] & 0xFF) << 16) | ((in[i + 1] & 0xFF) << 8) | (in[i + 2] & 0xFF);
            sb.append(ENC[v >>> 18]).append(ENC[(v >>> 12) & 63])
              .append(ENC[(v >>> 6) & 63]).append(ENC[v & 63]);
            i += 3;
        }
        int rem = in.length - i;
        if (rem == 1) {
            int v = (in[i] & 0xFF) << 16;
            sb.append(ENC[v >>> 18]).append(ENC[(v >>> 12) & 63]).append("==");
        } else if (rem == 2) {
            int v = ((in[i] & 0xFF) << 16) | ((in[i + 1] & 0xFF) << 8);
            sb.append(ENC[v >>> 18]).append(ENC[(v >>> 12) & 63])
              .append(ENC[(v >>> 6) & 63]).append('=');
        }
        return sb.toString();
    }

    /** 解码：容忍空白字符，遇 '=' 即止（尾部多余比特自然丢弃）。 */
    static byte[] decode(String s) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(s.length() * 3 / 4 + 3);
        int acc = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') continue;
            if (c == '=') break;
            int v = (c < 128) ? DEC[c] : -1;
            if (v < 0) throw new IllegalArgumentException("bad base64 char: " + c);
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                bos.write((acc >>> bits) & 0xFF);
            }
        }
        return bos.toByteArray();
    }
}
