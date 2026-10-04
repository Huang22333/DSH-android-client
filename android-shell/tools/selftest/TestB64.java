package app.dsh.shell;

/** B64 纯 Java 自测：RFC 4648 测试向量 + 0..255 全字节往返。 */
public final class TestB64 {
    public static void main(String[] args) {
        int fail = 0;
        String[][] vectors = {
            {"", ""},
            {"f", "Zg=="},
            {"fo", "Zm8="},
            {"foo", "Zm9v"},
            {"foob", "Zm9vYg=="},
            {"fooba", "Zm9vYmE="},
            {"foobar", "Zm9vYmFy"},
        };
        for (String[] v : vectors) {
            String enc = B64.encode(v[0].getBytes());
            if (!enc.equals(v[1])) {
                System.out.println("FAIL encode(" + v[0] + ")=" + enc + " 期望 " + v[1]);
                fail++;
            }
            String dec = new String(B64.decode(v[1]));
            if (!dec.equals(v[0])) {
                System.out.println("FAIL decode(" + v[1] + ")=" + dec + " 期望 " + v[0]);
                fail++;
            }
        }
        // 全字节往返
        byte[] all = new byte[256];
        for (int i = 0; i < 256; i++) all[i] = (byte) i;
        byte[] back = B64.decode(B64.encode(all));
        if (back.length != all.length) {
            System.out.println("FAIL 往返长度 " + back.length + " != " + all.length);
            fail++;
        } else {
            for (int i = 0; i < 256; i++) {
                if (back[i] != all[i]) {
                    System.out.println("FAIL 往返字节 " + i);
                    fail++;
                    break;
                }
            }
        }
        // 容忍空白 + '=' 后全忽略（非法字符只在 '=' 前才算错）
        byte[] messy = B64.decode("Zm9v\n YmFy  Zm9v====JUNK-junk");
        if (!new String(messy).equals("foobarfoo")) {
            System.out.println("FAIL 容忍空白/截断: " + new String(messy));
            fail++;
        }
        System.out.println(fail == 0 ? "TestB64 OK (13 项)" : "TestB64 FAILED " + fail);
        if (fail != 0) System.exit(1);
    }
}
