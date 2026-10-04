package app.dsh.shell;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 设置面板（自绘卡片，不依赖 AppCompat）。
 *
 * 为什么需要它：原来这些开关都塞在「长按返回键」的菜单里，但手势导航的手机
 * **根本没有返回键**，等于那些开关用户永远打不开。现在做成独立面板，
 * 既可以由桌面快捷方式打开，也可以由返回键菜单打开。
 */
final class SettingsPanel {

    private SettingsPanel() {}

    /** 一行开关的配置。 */
    interface OnToggle {
        /** 返回切换后的值。 */
        boolean onToggle(boolean nowOn);
    }

    /** 打开设置面板。 */
    static void show(final Activity a) {
        final SharedPreferences prefs = a.getSharedPreferences(Config.PREFS, 0);
        final Ui.Card card = Ui.card(a, true);
        final LinearLayout col = card.content;

        col.addView(Ui.title(a, "设置"));

        // 说明文字
        TextView hint = Ui.body(a, "这些设置会立刻生效并记住。");
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = Ui.dp(a, 8);
        col.addView(hint, hlp);

        // 运行环境（Android + WebView 版本）。装到老安卓上时靠它判断能不能跑 DSH。
        TextView envTitle = Ui.label(a, "运行环境");
        LinearLayout.LayoutParams etlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        etlp.topMargin = Ui.dp(a, 16);
        col.addView(envTitle, etlp);
        String envText = (a instanceof MainActivity)
                ? ((MainActivity) a).environmentReport()
                : ("Android " + android.os.Build.VERSION.SDK_INT);
        TextView env = Ui.body(a, envText);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = Ui.dp(a, 4);
        col.addView(env, elp);

        // ── 上传压缩 ──────────────────────────────────────────────
        addSwitchRow(a, col, "上传前压缩图片",
                "缩到 2048×2048 总像素内（正方形才恰 2048 长边，约 450KB）。"
                        + "DSH 会直接放行不再重编码，上传快很多；模型看到的画面完全不变。",
                prefs.getBoolean(Config.K_COMPRESS, true),
                now -> {
                    prefs.edit().putBoolean(Config.K_COMPRESS, now).apply();
                    return now;
                });

        // ── 调试通道（ADB） ──────────────────────────────────────────
        TextView dbgTitle = Ui.label(a, "调试通道（ADB）");
        LinearLayout.LayoutParams dtlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dtlp.topMargin = Ui.dp(a, 16);
        col.addView(dbgTitle, dtlp);

        TextView dbgHint = Ui.body(a,
                "开启后 DSH 主机可远程执行 shell 命令（经 Shizuku，等效 adb shell："
                        + "logcat / 装包 / 截图 / dumpsys）。手机不开任何端口，关掉即断。仅调试时开。"
                        + "地址填中继服务的域名（HTTPS）或内网 IP:端口（明文）。");
        LinearLayout.LayoutParams dhlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dhlp.topMargin = Ui.dp(a, 4);
        col.addView(dbgHint, dhlp);

        final EditText relayAddr = Ui.addField(col, a, "中继地址", "relay.example.com:443",
                prefs.getString(Config.K_RELAY_ADDR, ""), false);
        final EditText relayToken = Ui.addField(col, a, "令牌", "中继 token",
                prefs.getString(Config.K_RELAY_TOKEN, ""), true);

        final TextView dbgState = Ui.body(a, DebugRelay.describe());
        LinearLayout.LayoutParams dslp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dslp.topMargin = Ui.dp(a, 8);
        col.addView(dbgState, dslp);

        addSwitchRow(a, col, "调试通道", "连上 / 断开中继。开着 = 主机随时可执行命令。",
                prefs.getBoolean(Config.K_RELAY_ON, false),
                now -> {
                    if (now) {
                        String addr = relayAddr.getText().toString().trim();
                        String token = relayToken.getText().toString().trim();
                        if (addr.isEmpty() || token.isEmpty()) {
                            Toast.makeText(a, "先填中继地址和令牌", Toast.LENGTH_SHORT).show();
                            return false;
                        }
                        if (!DebugRelay.granted() && DebugRelay.shizukuAlive()) {
                            DebugRelay.requestPermission(1);
                            Toast.makeText(a, "请在 Shizuku 授权框里点允许，然后重新打开开关",
                                    Toast.LENGTH_LONG).show();
                            dbgState.setText(DebugRelay.describe());
                            return false;
                        }
                        String err = DebugRelay.start(addr, token);
                        if (!err.isEmpty()) {
                            Toast.makeText(a, err, Toast.LENGTH_LONG).show();
                            // Toast 会截断长错误，状态行给全文
                            dbgState.setText(err + "\n" + DebugRelay.describe());
                            return false;
                        }
                        prefs.edit().putString(Config.K_RELAY_ADDR, addr)
                                .putString(Config.K_RELAY_TOKEN, token)
                                .putBoolean(Config.K_RELAY_ON, true).apply();
                        // 不写"连接中"：服务自己会重连，面板不轮询连接状态，
                        // 写死"连接中"会误导（真机实测 1s 内就上线了）
                        dbgState.setText("通道：已开启（连 " + addr + "）\n"
                                + "是否在线以主机 phone status 为准；关掉开关即断");
                        return true;
                    }
                    DebugRelay.stop();
                    prefs.edit().putBoolean(Config.K_RELAY_ON, false).apply();
                    dbgState.setText("通道：已关闭");
                    return false;
                });

        // ── 关闭按钮 ──────────────────────────────────────────────
        TextView close = Ui.primaryButton(a, "完成");
        Ui.addButtons(col, a, close);
        close.setOnClickListener(v -> card.dialog.dismiss());

        card.dialog.show();
    }

    /**
     * 一行开关：左标题+说明，右侧一个自绘的开关（不用 Switch 控件，
     * 各 ROM 外观差异太大，且 Material Switch 要额外依赖）。
     */
    private static void addSwitchRow(final Activity a, LinearLayout col, String title,
                                     String desc, boolean initial,
                                     final OnToggle toggle) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int ph = Ui.dp(a, 14);
        row.setPadding(ph, ph, ph, ph);
        row.setBackground(Ui.round(Ui.field(), 14f, a));
        row.setClickable(true);
        row.setFocusable(true);

        LinearLayout text = new LinearLayout(a);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(16f);
        t.setTextColor(Ui.text());
        t.setIncludeFontPadding(false);
        text.addView(t);
        TextView d = new TextView(a);
        d.setText(desc);
        d.setTextSize(12.5f);
        d.setTextColor(Ui.sub());
        d.setLineSpacing(Ui.dp(a, 2), 1f);
        d.setIncludeFontPadding(false);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = Ui.dp(a, 3);
        text.addView(d, dlp);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(text, tlp);

        final TextView sw = switchView(a, initial);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = Ui.dp(a, 14);
        row.addView(sw, slp);

        final boolean[] state = {initial};
        row.setOnClickListener(v -> {
            state[0] = toggle.onToggle(!state[0]);
            paintSwitch(a, sw, state[0]);
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(a, 10);
        col.addView(row, lp);
    }

    private static TextView switchView(Activity a, boolean on) {
        TextView sw = new TextView(a);
        sw.setGravity(Gravity.CENTER);
        sw.setTextSize(13f);
        sw.setTypeface(sw.getTypeface(), android.graphics.Typeface.BOLD);
        paintSwitch(a, sw, on);
        return sw;
    }

    private static void paintSwitch(Activity a, TextView sw, boolean on) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(Ui.dp(a, 999));          // 全圆角 = 胶囊
        bg.setColor(on ? Ui.ACCENT : 0xFFCBD5E1);
        sw.setBackground(bg);
        sw.setText(on ? "已开启" : "已关闭");
        sw.setTextColor(0xFFFFFFFF);
        int hx = Ui.dp(a, 16);
        int hy = Ui.dp(a, 8);
        sw.setPadding(hx, hy, hx, hy);
    }

    /** 供返回键菜单复用：只做一个入口按钮。 */
    static void addEntryRow(Activity a, LinearLayout col, final Runnable onOpen) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.VERTICAL);
        int ph = Ui.dp(a, 14);
        row.setPadding(ph, ph, ph, ph);
        row.setBackground(Ui.round(Ui.field(), 14f, a));
        TextView t = new TextView(a);
        t.setText("设置");
        t.setTextSize(16f);
        t.setTextColor(Ui.text());
        t.setIncludeFontPadding(false);
        row.addView(t);
        TextView d = new TextView(a);
        d.setText("上传压缩等开关");
        d.setTextSize(12.5f);
        d.setTextColor(Ui.sub());
        d.setIncludeFontPadding(false);
        row.addView(d);
        row.setOnClickListener(v -> onOpen.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(a, 10);
        col.addView(row, lp);
    }
}
