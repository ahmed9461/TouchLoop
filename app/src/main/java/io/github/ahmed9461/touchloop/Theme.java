package io.github.ahmed9461.touchloop;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.widget.TextView;

final class Theme {
    static final int BACK = 0xff101819;
    static final int PANEL = 0xff1c282a;
    static final int STROKE = 0xff334648;
    static final int INK = 0xfff1faf8;
    static final int MUTED = 0xffb1c5c4;
    static final int MINT = 0xff72e4c6;
    static final int DARK_MINT = 0xff14332d;
    static final int DANGER = 0xffffb4a9;

    private Theme() {}

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable shape(Context context, int color, int corner, int border) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(context, corner));
        if (border != 0) shape.setStroke(dp(context, 1), border);
        return shape;
    }

    static TextView text(Context context, String value, int size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(size);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    static TextView action(Context context, String title, int background, int foreground, Runnable command) {
        TextView button = text(context, title, 14, foreground, true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(context, 12), 0, dp(context, 12), 0);
        button.setMinHeight(dp(context, 48));
        button.setMinWidth(dp(context, 48));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x338aabaa),
                shape(context, background, 12, 0), null));
        button.setFocusable(true);
        button.setOnClickListener(view -> command.run());
        button.setAccessibilityDelegate(new android.view.View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(android.view.View view,
                    android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(view, info);
                info.setClassName(android.widget.Button.class.getName());
            }
        });
        return button;
    }
}
