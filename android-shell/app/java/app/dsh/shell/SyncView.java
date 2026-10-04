package app.dsh.shell;

import android.content.Context;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 「正在同步」小胶囊：从后台切回前台时，DSH 前端要重连并重拉会话历史，
 * 这几秒里页面是停住的（主线程在重渲染），看起来像服务器死了。
 *
 * ⚠️ **必须由原生画，不能在网页里画**：卡住的正是 WebView 的主线程，
 * 网页里画的转圈会跟着一起卡死，等于没提示。原生 View 是独立渲染，照样转。
 * 这也是为什么不在注入脚本里做这件事。
 *
 * 故意做成小胶囊而不是全屏遮罩：全屏会把刚看到的会话内容盖住，反而更烦。
 * 它也不吃点击（{@code setClickable(false)}），不挡任何操作。
 */
final class SyncView extends FrameLayout {

    /** 「同步完成」停留多久再淡出。 */
    private static final long DONE_LINGER_MS = 700L;

    private final LoadingView.ProgressRing ring;
    private final TextView text;
    private final LinearLayout box;
    private boolean shown;
    /** 当前是不是"同步完成"态。 */
    private boolean done;

    SyncView(Context c) {
        super(c);
        setClickable(false);
        setFocusable(false);

        box = new LinearLayout(c);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(Ui.dp(c, 12), Ui.dp(c, 7), Ui.dp(c, 14), Ui.dp(c, 7));
        // 浅色主题下胶囊是白的、页面也是白的，所以要描边 + 投影才看得出来
        box.setBackground(Ui.round(Ui.card(), 999f, c, Ui.dark ? 0xFF243044 : 0xFFE2E8F0, 1f));
        box.setElevation(Ui.dp(c, 3));

        ring = new LoadingView.ProgressRing(c);
        box.addView(ring, new LinearLayout.LayoutParams(Ui.dp(c, 15), Ui.dp(c, 15)));

        text = new TextView(c);
        text.setText("正在同步…");
        text.setTextSize(12f);
        text.setIncludeFontPadding(false);
        text.setTextColor(Ui.text());
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = Ui.dp(c, 8);
        box.addView(text, tlp);

        LayoutParams lp = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        // 初值随便给个安全值；真正的让位在 MainActivity 的 insets 回调里按挖孔/状态栏算
        lp.topMargin = Ui.dp(c, 56);
        addView(box, lp);

        setAlpha(0f);
        setVisibility(GONE);
    }

    /**
     * 有数据在传 → 显示"正在同步…"（转圈）。
     * 只有**真的观测到网络流量**才会被调用，所以没有流量的锁屏/解锁不会再弹（用户实报的问题）。
     */
    void showBusy() {
        if (done) {
            return;                     // 已经宣布完成了，别再倒回"正在同步"
        }
        ring.setColors(Ui.ACCENT, Ui.field());
        text.setTextColor(Ui.text());
        setTextWithRing("正在同步…", true);
        if (shown) {
            return;
        }
        shown = true;
        setVisibility(VISIBLE);
        animate().alpha(1f).setDuration(140).start();
    }

    /** 数据传完 → 切成"同步完成"（不再转圈），停留一下再淡出。 */
    void showDone() {
        if (!shown) {
            return;                     // 压根没显示过就什么都不做
        }
        done = true;
        setTextWithRing("同步完成", false);
        text.setTextColor(0xFF16A34A);  // 绿色对勾感
        animate().alpha(0f).setStartDelay(DONE_LINGER_MS).setDuration(220)
                .withEndAction(this::afterHide).start();
    }

    /** 立刻收起（切后台、兜底、新一轮同步开始前）。 */
    void hide() {
        if (!shown) {
            return;
        }
        animate().cancel();
        ring.stop();
        animate().alpha(0f).setDuration(140).withEndAction(this::afterHide).start();
    }

    private void afterHide() {
        shown = false;
        done = false;
        setVisibility(GONE);
    }

    /** 转圈与文字：完成态把圈藏掉，文字也就跟着靠左收回来。 */
    private void setTextWithRing(String s, boolean spin) {
        text.setText(s);
        ring.setVisibility(spin ? VISIBLE : GONE);
        if (spin) {
            ring.start();
        } else {
            ring.stop();
        }
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) text.getLayoutParams();
        int want = spin ? Ui.dp(getContext(), 8) : 0;
        if (lp.leftMargin != want) {
            lp.leftMargin = want;
            text.setLayoutParams(lp);
        }
    }

    boolean isVisible() {
        return shown;
    }
}
