package app.dsh.shell;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;

import rikka.shizuku.Shizuku;

/**
 * 调试通道控制端（跑在 App 进程）。
 *
 * 只负责：查/要 Shizuku 授权 → 把 {@link DebugRelayService} 拉起来 → 下发配置 → 断开。
 * 真正干活的 DebugRelayService 在 Shizuku 的 shell 身份进程里，与 App 同生共死无关。
 *
 * 事务约定（裸 Binder，两边都是我们的代码）：见 DebugRelayService 头注释。
 */
final class DebugRelay {
    private DebugRelay() {}

    private static final String PKG = "app.dsh.shell";
    private static final String SERVICE_CLS = "app.dsh.shell.DebugRelayService";
    private static ServiceConnection conn;
    private static IBinder service;
    private static String pendingAddr = "";
    private static String pendingToken = "";

    private static Shizuku.UserServiceArgs args() {
        return new Shizuku.UserServiceArgs(new ComponentName(PKG, SERVICE_CLS))
                .tag("dsh-relay").version(2).daemon(true)
                // ⚠️ processNameSuffix 必设：Shizuku 的 forAdd() 对 processName 做
                // requireNonNull，不设直接 NPE（"process name suffix must not be null"）
                .processNameSuffix("relay");
    }

    static boolean shizukuAlive() {
        try { return Shizuku.pingBinder(); } catch (Throwable t) { return false; }
    }

    static boolean granted() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) { return false; }
    }

    /** 一句话状态，给设置面板显示。 */
    static String describe() {
        try {
            if (!Shizuku.pingBinder()) {
                return "Shizuku：未运行（先在 Shizuku App 里启动它）";
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return "Shizuku：未授权（开开关会弹授权框）";
            }
            int uid = Shizuku.getUid();
            return "Shizuku：已连接（" + (uid == 0 ? "root" : "shell") + " 权限）";
        } catch (Throwable t) {
            return "Shizuku：不可用（" + t + "）";
        }
    }

    static void requestPermission(int requestCode) {
        try { Shizuku.requestPermission(requestCode); } catch (Throwable ignored) {}
    }

    /**
     * 开启调试通道。返回空串=已发出启动指令；非空=错误描述（面板直接显示）。
     * 连接是异步的：这里只保证 bind + 下发配置成功。
     */
    static synchronized String start(String addr, String token) {
        pendingAddr = addr;
        pendingToken = token;
        if (!shizukuAlive()) return "Shizuku 未运行";
        if (!granted()) return "Shizuku 未授权";
        try {
            if (conn == null) {
                conn = new ServiceConnection() {
                    public void onServiceConnected(ComponentName n, IBinder b) {
                        service = b;
                        sendStart();
                    }
                    public void onServiceDisconnected(ComponentName n) {
                        service = null;
                    }
                };
                Shizuku.bindUserService(args(), conn);
            } else if (service != null) {
                sendStart();
            }
            return "";
        } catch (Throwable t) {
            // 完整堆栈进 logcat（通道通了以后我能看到），Toast 只给一行
            try { android.util.Log.w("DshRelay", android.util.Log.getStackTraceString(t)); }
            catch (Throwable ignored) {}
            return "启动失败：" + t;
        }
    }

    /** App 重启后按已存配置悄悄接回（Shizuku 不在/未授权就放弃，不弹框）。 */
    static void restore(String addr, String token) {
        if (addr.isEmpty() || token.isEmpty()) return;
        if (granted()) start(addr, token);   // 错误忽略：静默恢复
    }

    /** 关闭调试通道：让服务端自杀 + 解绑。 */
    static synchronized void stop() {
        IBinder b = service;
        if (b != null) {
            Parcel data = Parcel.obtain();
            try {
                b.transact(DebugRelayService.CODE_STOP, data, null, 0);
            } catch (Throwable ignored) {
            } finally {
                data.recycle();
            }
            service = null;
        }
        try {
            if (conn != null) Shizuku.unbindUserService(args(), conn, true);
        } catch (Throwable ignored) {}
        conn = null;
    }

    private static void sendStart() {
        IBinder b = service;
        if (b == null) return;
        String host;
        int port = 2900;
        int i = pendingAddr.lastIndexOf(':');
        if (i > 0) {
            host = pendingAddr.substring(0, i);
            try { port = Integer.parseInt(pendingAddr.substring(i + 1)); }
            catch (NumberFormatException ignored) {}
        } else {
            host = pendingAddr;
        }
        Parcel data = Parcel.obtain();
        try {
            data.writeString(host);
            data.writeInt(port);
            data.writeString(pendingToken);
            data.writeString(Build.MODEL);
            b.transact(DebugRelayService.CODE_START, data, null, 0);
        } catch (Throwable ignored) {
        } finally {
            data.recycle();
        }
    }
}
