package app.dsh.shell;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 全屏加载页。
 *
 * 存在的意义不只是好看：WebView 加载 13MB 资源时首页要等好几秒，
 * 这段时间**什么都不显示**会让人以为 App 死了（用户原话："莫名其妙白屏"）。
 * 所以这里显示：
 *   · 一圈不确定进度环（旋转）——表示"在动，没死"
 *   · 已用时秒数一直在跳 —— 数字停住就是真卡了
 *   · 当前阶段文案（连接 / 加载资源 / 渲染）
 *
 * 不依赖任何第三方库，纯自绘；配色随页面主题（深浅色）切换。
 */
final class LoadingView extends FrameLayout {

    private final ImageView logo;
    private final ProgressRing ring;
    private final TextView title;
    private final TextView detail;
    private final TextView hint;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long startedAt;
    private boolean running;
    private String stage = "正在连接";

    /** 每秒刷新一次已用时。 */
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            long sec = (System.currentTimeMillis() - startedAt) / 1000;
            detail.setText(stage + " · " + sec + "s");
            handler.postDelayed(this, 250);
        }
    };

    private int bgColor = 0xFF0B1220;
    private int textColor = 0xFFE8EDF7;
    private int subColor = 0xFF94A3B8;
    private int accent = 0xFF2F6BFF;

    LoadingView(Context c) {
        super(c);
        setBackgroundColor(bgColor);
        setClickable(true);              // 吞掉点击，别穿透到底下的 WebView

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        logo = new ImageView(c);
        try {
            logo.setImageResource(R.drawable.whale_white);
        } catch (Exception ignored) {
            // 图标缺失也不影响使用
        }
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                Ui.dp(c, 108), Ui.dp(c, 108));
        box.addView(logo, llp);

        ring = new ProgressRing(c);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                Ui.dp(c, 34), Ui.dp(c, 34));
        rlp.topMargin = Ui.dp(c, 26);
        box.addView(ring, rlp);

        title = new TextView(c);
        title.setText("正在加载 DSH");
        title.setTextSize(17f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setIncludeFontPadding(false);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.dp(c, 20);
        box.addView(title, tlp);

        detail = new TextView(c);
        detail.setTextSize(13f);
        detail.setIncludeFontPadding(false);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = Ui.dp(c, 8);
        box.addView(detail, dlp);

        // 第三行：页面自报的处境（资源数/文字数/输入框有无/JS 报错）。
        // 手机上没有 adb，卡住时这一行就是唯一的线索 —— 截个图就能定位。
        // 只在页面还没就绪时出现，正常运行几乎看不见。
        hint = new TextView(c);
        hint.setTextSize(10.5f);
        hint.setIncludeFontPadding(false);
        hint.setGravity(Gravity.CENTER);
        hint.setMaxLines(3);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                Ui.dp(c, 260), ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = Ui.dp(c, 12);
        box.addView(hint, hlp);

        addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        applyColors();
    }

    // ───────────────────────── 对外接口 ─────────────────────────

    /** 显示并开始计时。 */
    void start() {
        startedAt = System.currentTimeMillis();
        stage = "正在连接";
        running = true;
        ring.start();
        setAlpha(1f);
        setVisibility(VISIBLE);
        hint.setText("");
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    /** 页面自报的处境；见 MainActivity.THEME_SCRIPT ④。 */
    void setHint(String s) {
        hint.setText(s);
    }

    /** 更新阶段文案（连接 → 加载资源 → 渲染）。 */
    void setStage(String s) {
        stage = s;
        long sec = (System.currentTimeMillis() - startedAt) / 1000;
        detail.setText(s + " · " + sec + "s");
    }

    /**
     * 淡出并停止计时。
     *
     * ⚠️ 必须**先转成硬件层再淡出**：加载页消失时，底下的 WebView 正在做最重的
     * 首屏渲染（12MB 插件包 + React 首次提交），若这 220ms 里每帧都重新合成
     * 整棵视图树，就会掉帧 —— 表现正是用户说的「从"正在渲染"到进入页面明显卡顿」。
     * 提到硬件层后，淡出退化为纯纹理透明度变化，不再触碰 WebView 的重绘。
     */
    void finish() {
        // 允许"失败态之后页面又起来了"能把它收掉（fail() 把 running 置了 false）
        if (!running && getVisibility() != VISIBLE) {
            return;
        }
        running = false;
        handler.removeCallbacks(ticker);
        ring.stop();
        setLayerType(LAYER_TYPE_HARDWARE, null);
        animate().alpha(0f).setDuration(160).withEndAction(() -> {
            setVisibility(GONE);
            setAlpha(1f);
            // 用完就撤掉硬件层，免得这块常驻显存
            setLayerType(LAYER_TYPE_NONE, null);
        }).start();
    }

    /** 加载失败时把加载页留着，避免闪回白屏；文案说明情况。 */
    /**
     * 失败态：把加载页留在屏幕上当说明用（比收起后露白屏有用得多）。
     * 标题给结论，第三行给可操作的建议；不再显示"已用 Ns"（那是加载中的语义）。
     */
    void fail(String title, String advice) {
        running = false;
        handler.removeCallbacks(ticker);
        ring.stop();
        setVisibility(VISIBLE);
        setAlpha(1f);
        stage = title;
        this.title.setText(title);
        detail.setVisibility(VISIBLE);
        long sec = (System.currentTimeMillis() - startedAt) / 1000;
        detail.setText("已等 " + sec + "s");
        setHint(advice);
    }

    /** 主题色：跟随页面底色切深浅。 */
    void applyTheme(boolean dark) {
        bgColor = dark ? 0xFF0B1220 : 0xFFFFFFFF;
        textColor = dark ? 0xFFE8EDF7 : 0xFF0F172A;
        subColor = dark ? 0xFF94A3B8 : 0xFF64748B;
        applyColors();
    }

    private void applyColors() {
        setBackgroundColor(bgColor);
        title.setTextColor(textColor);
        detail.setTextColor(subColor);
        hint.setTextColor(subColor);
        ring.setColors(accent, bgColor == 0xFFFFFFFF ? 0xFFE2E8F0 : 0xFF1E293B);
        // 浅色底上用深色鲸鱼，深色底上用白鲸鱼
        try {
            logo.setImageResource(bgColor == 0xFFFFFFFF
                    ? R.mipmap.ic_launcher : R.drawable.whale_white);
        } catch (Exception ignored) {
            // 忽略
        }
    }

    boolean isActive() {
        return running;
    }

    // ───────────────────────── 旋转进度环 ─────────────────────────

    /**
     * 不确定进度环：一段圆弧绕圈转。
     * 不用 ProgressBar 是因为它各 ROM 外观差异大，且不好配对配色。
     */
    static final class ProgressRing extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private float angle;
        private ValueAnimator anim;
        private int fg = 0xFF2F6BFF;
        private int bgTrack = 0xFF1E293B;

        ProgressRing(Context c) {
            super(c);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        void setColors(int foreground, int track) {
            fg = foreground;
            bgTrack = track;
            invalidate();
        }

        void start() {
            if (anim != null && anim.isRunning()) {
                return;
            }
            anim = ValueAnimator.ofFloat(0f, 360f);
            anim.setDuration(900);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(a -> {
                angle = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }

        void stop() {
            if (anim != null) {
                anim.cancel();
                anim = null;
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            stop();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float stroke = Ui.dp(getContext(), 3.5f);
            paint.setStrokeWidth(stroke);
            float inset = stroke / 2f + 1f;
            oval.set(inset, inset, getWidth() - inset, getHeight() - inset);

            paint.setColor(bgTrack);
            canvas.drawArc(oval, 0, 360, false, paint);

            paint.setColor(fg);
            // 约 1/4 圈的弧，绕中心旋转
            canvas.drawArc(oval, angle, 90, false, paint);
        }
    }
}
