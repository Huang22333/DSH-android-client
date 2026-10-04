package app.dsh.shell;

import android.os.Binder;
import android.os.Parcel;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 调试通道的执行端（Shizuku UserService）。
 *
 * 由 Shizuku 以 shell(uid 2000) 身份在**独立进程**里拉起，不依赖 Activity ——
 * 所以 App 被杀、崩溃都不影响通道（正好用来看崩溃日志）。它做的事：
 * 主动出站连中继 → 收 shell 命令 → 本地 sh -c 执行 → 输出流回去。
 * 手机不开任何监听端口，等效「远程 adb shell」。
 *
 * 与 App 进程的控制接口（无 AIDL，裸 Binder 事务，两边都是我们的代码）：
 *   1 = START：依次读 host(String) port(int) token(String) name(String)
 *   2 = STOP：断开并自杀
 *   16777115 / 16777114 = Shizuku 约定的 destroy（unbind 时服务端会发）
 *
 * 协议见 phone-relay/relay.py（仓库根）头部注释（行式文本 + base64）。
 */
public final class DebugRelayService extends Binder {

    static final int CODE_START = 1;
    static final int CODE_STOP = 2;
    static final int CODE_DESTROY = 16777115;   // Shizuku 约定；AIDL 声明写 16777114，实发 16777115
    static final int CHUNK = 16 * 1024;

    private final Object wlock = new Object();
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private volatile boolean running;
    private volatile Socket sock;
    private volatile OutputStream out;

    static final class Job {
        final Process proc;
        final AtomicBoolean ended = new AtomicBoolean(false);
        Job(Process p) { proc = p; }
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws android.os.RemoteException {
        switch (code) {
            case CODE_START: {
                String host = data.readString();
                int port = data.readInt();
                String token = data.readString();
                String name = data.readString();
                startRelay(host, port, token, name);
                return true;
            }
            case CODE_STOP:
            case CODE_DESTROY:
            case 16777114:
                shutdown();
                return true;
            default:
                return super.onTransact(code, data, reply, flags);
        }
    }

    // ---------------- 启停 ----------------

    private void startRelay(final String host, final int port,
                            final String token, final String name) {
        if (running) return;
        running = true;
        Thread t = new Thread(new Runnable() {
            public void run() { relayLoop(host, port, token, name); }
        }, "dsh-relay");
        t.setDaemon(true);
        t.start();
    }

    private void shutdown() {
        running = false;
        for (Job job : jobs.values()) {
            try { job.proc.destroy(); } catch (Throwable ignored) {}
        }
        closeQuietly(sock);
        sock = null;
        out = null;
        // 不退出的话 daemon 模式会一直挂着（通道"关闭"必须是真的关掉）
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        System.exit(0);
    }

    // ---------------- 中继主循环 ----------------

    private void relayLoop(String host, int port, String token, String name) {
        while (running) {
            Socket s = null;
            try {
                s = connect(host, port);
                sock = s;
                out = s.getOutputStream();
                send("HELLO " + token + " PHONE " + name);
                BufferedReader r = new BufferedReader(new InputStreamReader(
                        s.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while (running && (line = r.readLine()) != null) {
                    onLine(line.trim());
                }
            } catch (Throwable ignored) {
                // 掉线就 3s 后重连
            } finally {
                closeQuietly(s);
                sock = null;
                out = null;
            }
            if (!running) return;
            try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
        }
    }

    /**
     * 建连：域名走 TLS（系统信任库 + 主机名校验，证书是反代网关上的真证书），
     * 内网 IP 直连明文（家里 LAN 免绕公网）。判据就一条：host 是不是 IP 字面量。
     */
    private Socket connect(String host, int port) throws Exception {
        Socket s;
        if (host.matches("[0-9.]+") || host.indexOf(':') >= 0) {   // IPv4 / IPv6 字面量
            s = new Socket();
            s.connect(new InetSocketAddress(host, port), 10000);
        } else {
            javax.net.ssl.SSLSocketFactory f = (javax.net.ssl.SSLSocketFactory)
                    javax.net.ssl.SSLSocketFactory.getDefault();
            javax.net.ssl.SSLSocket ssl =
                    (javax.net.ssl.SSLSocket) f.createSocket();
            javax.net.ssl.SSLParameters p = ssl.getSSLParameters();
            // 必须开主机名校验：默认 SSLSocket 只验链不验名，
            // 任何可信 CA 签的任意域名证书都能中间人偷走 token
            p.setEndpointIdentificationAlgorithm("HTTPS");
            ssl.setSSLParameters(p);
            ssl.connect(new InetSocketAddress(host, port), 10000);
            ssl.startHandshake();
            s = ssl;
        }
        s.setTcpNoDelay(true);
        return s;
    }

    private void onLine(String line) {
        if (line.equals("PING")) { send("PONG"); return; }
        String[] p = line.split(" ", 3);
        if (p.length == 3 && p[0].equals("EXEC")) {
            String cmd;
            try {
                cmd = new String(B64.decode(p[2]), StandardCharsets.UTF_8);
            } catch (Throwable t) {
                send("END " + p[1] + " 127");
                return;
            }
            execAsync(p[1], cmd);
        } else if (p.length >= 2 && p[0].equals("KILL")) {
            Job job = jobs.get(p[1]);
            if (job != null) {
                try { job.proc.destroy(); } catch (Throwable ignored) {}
                // 立刻回 END：等管道自然关闭会被拖很久（子进程不退的话）
                finish(p[1], job, -9);
            }
        }
    }

    // ---------------- 命令执行 ----------------

    private void execAsync(final String id, final String cmd) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                Job job = null;
                try {
                    ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", cmd);
                    Process p = pb.start();
                    job = new Job(p);
                    jobs.put(id, job);
                    Thread o = pump(id, 1, p.getInputStream());
                    Thread e = pump(id, 2, p.getErrorStream());
                    o.start();
                    e.start();
                    o.join();
                    e.join();
                    finish(id, job, p.waitFor());
                } catch (Throwable t) {
                    if (job != null) finish(id, job, 127);
                    else send("END " + id + " 127");
                } finally {
                    jobs.remove(id);
                }
            }
        }, "dsh-exec");
        t.setDaemon(true);
        t.start();
    }

    private Thread pump(final String id, final int fd, final InputStream in) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                byte[] buf = new byte[CHUNK];
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        byte[] chunk = new byte[n];
                        System.arraycopy(buf, 0, chunk, 0, n);
                        send("OUT " + id + " " + fd + " " + B64.encode(chunk));
                    }
                } catch (Throwable ignored) {}
            }
        }, "dsh-pump");
        t.setDaemon(true);
        return t;
    }

    private void finish(String id, Job job, int code) {
        if (job.ended.compareAndSet(false, true)) {
            send("END " + id + " " + code);
        }
    }

    // ---------------- 收发 ----------------

    private void send(String line) {
        OutputStream o = out;
        if (o == null) return;
        synchronized (wlock) {
            try {
                o.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                o.flush();
            } catch (Throwable ignored) {}
        }
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try { s.close(); } catch (Throwable ignored) {}
    }
}
