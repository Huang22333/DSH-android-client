package app.dsh.shell;

/**
 * 编译期常量。
 *
 * 刻意**不写死任何地址** —— 首次进入由用户手填，之后记在应用私有存储里，
 * 这样同一个 APK 可以给任何自建 DSH 的人用。
 */
public final class Config {
    private Config() {}

    public static final String PREFS = "dsh";
    public static final String K_URL = "url";
    public static final String K_USER = "user";
    public static final String K_PASS = "pass";
    public static final String K_REMEMBER = "remember";
    /** 上传前压缩图片（默认开）。 */
    public static final String K_COMPRESS = "compressUploads";
    /** 调试通道（Shizuku 中继）：地址 host:port、令牌、开关状态（都由用户手填）。 */
    public static final String K_RELAY_ADDR = "relayAddr";
    public static final String K_RELAY_TOKEN = "relayToken";
    public static final String K_RELAY_ON = "relayOn";
}
