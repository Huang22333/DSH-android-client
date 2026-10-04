package app.dsh.shell;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.Build;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 自绘对话框：系统 AlertDialog 在 Android 15 上仍是旧 Material 观感（用户评"像老安卓"），
 * 这里统一用自绘卡片，颜色随页面主题走。
 *
 * 全部用代码构建，不引 AppCompat/Material 依赖 —— 那样会把 58KB 的包撑成几 MB，
 * 而这里只需要几个静态控件。
 */
final class Ui {

    private Ui() {}

    // 浅色主题
    static final int BG = 0xFFFFFFFF;
    static final int CARD = 0xFFFFFFFF;
    static final int TEXT = 0xFF0F172A;
    static final int SUB = 0xFF64748B;
    static final int ACCENT = 0xFF2F6BFF;
    static final int FIELD = 0xFFF1F5F9;
    static final int SCRIM = 0x99000000;

    // 深色主题
    static final int D_BG = 0xFF0B1220;
    static final int D_CARD = 0xFF151E2E;
    static final int D_TEXT = 0xFFE8EDF7;
    static final int D_SUB = 0xFF94A3B8;
    static final int D_FIELD = 0xFF1E293B;

    static boolean dark = false;

    static int bg() {
        return dark ? D_BG : BG;
    }

    static int card() {
        return dark ? D_CARD : CARD;
    }

    static int text() {
        return dark ? D_TEXT : TEXT;
    }

    static int sub() {
        return dark ? D_SUB : SUB;
    }

    static int field() {
        return dark ? D_FIELD : FIELD;
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    static GradientDrawable round(int fill, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    static GradientDrawable round(int fill, float radiusDp, Context c, int strokeColor,
                                  float strokeDp) {
        GradientDrawable d = round(fill, radiusDp, c);
        d.setStroke(dp(c, strokeDp), strokeColor);
        return d;
    }

    /** 一个带圆角的实心/描边块。 */
    static View box(Context c, int fill, float radiusDp) {
        View v = new View(c);
        v.setBackground(round(fill, radiusDp, c));
        return v;
    }

    static TextView title(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(21f);
        t.setTextColor(text());
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setIncludeFontPadding(false);
        return t;
    }

    static TextView body(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(14f);
        t.setTextColor(sub());
        t.setLineSpacing(dp(c, 3), 1f);
        t.setIncludeFontPadding(false);
        return t;
    }

    static TextView label(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(12.5f);
        t.setTextColor(sub());
        t.setIncludeFontPadding(false);
        return t;
    }

    /** 圆角输入框（浅灰底、无边框，焦点时描边强调色）。 */
    static EditText input(Context c, String hint, String initial, boolean password) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setText(initial);
        e.setSingleLine(true);
        e.setTextSize(16f);
        e.setTextColor(text());
        e.setHintTextColor(sub());
        e.setBackground(round(field(), 14f, c));
        int px = dp(c, 16);
        e.setPadding(px, dp(c, 14), px, dp(c, 14));
        e.setInputType(password
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        if (initial != null) {
            e.setSelection(initial.length());
        }
        return e;
    }

    /** 主按钮：实心强调色 + 圆角 + 涟漪，够现代了。 */
    static TextView primaryButton(Context c, String s) {
        return button(c, s, ACCENT, 0xFFFFFFFF, true);
    }

    static TextView ghostButton(Context c, String s) {
        return button(c, s, Color.TRANSPARENT, text(), false);
    }

    private static TextView button(Context c, String s, int fill, int textColor,
                                   boolean filled) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(15.5f);
        t.setTextColor(textColor);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(dp(c, 20), dp(c, 13), dp(c, 20), dp(c, 13));
        GradientDrawable base = filled
                ? round(fill, 14f, c)
                : round(Color.TRANSPARENT, 14f, c);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ColorStateList ripple = ColorStateList.valueOf(
                    filled ? 0x44FFFFFF : 0x22000000);
            t.setBackground(new RippleDrawable(ripple, base, null));
        } else {
            t.setBackground(base);
        }
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    /** 卡片对话框：dialog 用于 show()/dismiss()，content 往里塞子控件。 */
    static final class Card {
        final Dialog dialog;
        final LinearLayout content;
        Card(Dialog d, LinearLayout c) {
            this.dialog = d;
            this.content = c;
        }
    }

    /**
     * 卡片式对话框骨架，颜色与圆角统一在这里。
     *
     * ⚠️ 窗口必须 MATCH_PARENT × MATCH_PARENT 并把遮罩画在自己的根视图上：
     * 若用 WRAP_CONTENT 高度，遮罩只会覆盖卡片那一小块，屏幕其余部分不暗，
     * 观感会很怪（用 FLAG_DIM_BEHIND 又会和自绘遮罩叠成双重变暗）。
     */
    static Card card(Activity a, boolean cancelable) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(a, 22);
        content.setPadding(pad, dp(a, 22), pad, dp(a, 12));
        content.setBackground(round(card(), 26f, a));

        FrameLayout rootView = new FrameLayout(a);
        rootView.setBackgroundColor(SCRIM);
        rootView.setClickable(true);          // 吞掉点击，避免穿透到底下的 WebView

        // 卡片外面套一层 ScrollView：小屏 + 键盘弹起时内容变高，卡片可能超出屏幕，
        // 没有这层就只能被裁掉（登录框三个输入控件尤其容易顶出去）。
        ScrollView scroller = new ScrollView(a);
        scroller.setFillViewport(false);
        int margin = dp(a, 22);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(margin, margin, margin, margin);
        slp.gravity = Gravity.CENTER;
        scroller.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rootView.addView(scroller, slp);

        d.setContentView(rootView);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        d.setCancelable(cancelable);
        d.setCanceledOnTouchOutside(cancelable);
        return new Card(d, content);
    }

    static EditText addField(LinearLayout col, Context c, String labelText, String hint,
                             String initial, boolean password) {
        if (labelText != null) {
            TextView l = label(c, labelText);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(c, 18);
            lp.bottomMargin = dp(c, 7);
            col.addView(l, lp);
        }
        EditText e = input(c, hint, initial, password);
        col.addView(e, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    static CheckBox addCheck(LinearLayout col, Context c, String s, boolean checked) {
        CheckBox cb = new CheckBox(c);
        cb.setText(s);
        cb.setTextSize(14f);
        cb.setTextColor(text());
        cb.setChecked(checked);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 14);
        col.addView(cb, lp);
        return cb;
    }

    /** 底部按钮行：右对齐，副按钮在前。 */
    static LinearLayout addButtons(LinearLayout col, Context c, TextView... buttons) {        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        for (int i = 0; i < buttons.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                lp.leftMargin = dp(c, 8);
            }
            row.addView(buttons[i], lp);
        }
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(c, 20);
        col.addView(row, rlp);
        return row;
    }

    static void showKeyboard(EditText e) {
        e.requestFocus();
        InputMethodManager imm = (InputMethodManager)
                e.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(e, InputMethodManager.SHOW_IMPLICIT);
        }
    }
}
