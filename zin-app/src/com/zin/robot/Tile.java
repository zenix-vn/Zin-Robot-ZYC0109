package com.zin.robot;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Icon (+ optional caption) control used for every button in the app, so they all share one look. */
class Tile extends LinearLayout {
    interface OnHold { void hold(boolean down); }

    private final ImageView icon;
    private final TextView label;
    private int iconRes;
    private final boolean circle;
    private final float radiusDp;
    private boolean active;
    private int activeColor = Ui.ACCENT;

    /** circle = round icon button (no caption); otherwise a rounded tile with the caption under the icon. */
    Tile(Context c, int iconRes, String caption, boolean circle, int iconDp) {
        super(c);
        this.iconRes = iconRes;
        this.circle = circle;
        this.radiusDp = circle ? 0 : 18;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setClickable(true);
        icon = new ImageView(c);
        addView(icon, new LayoutParams(Ui.dp(c, iconDp), Ui.dp(c, iconDp)));
        label = Ui.text(c, caption == null ? "" : caption, 12, Ui.MUTED, Ui.MEDIUM);
        label.setGravity(Gravity.CENTER);
        if (caption != null) {
            LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(c, 6);
            addView(label, lp);
        }
        refresh();
    }

    void setActiveColor(int c) { activeColor = c; refresh(); }

    void setActive(boolean a) {
        if (active == a) return;
        active = a;
        refresh();
    }

    boolean isActive() { return active; }

    void setIcon(int res) {
        iconRes = res;
        refresh();
    }

    void setCaption(String s) { label.setText(s); }

    private void refresh() {
        Context c = getContext();
        Drawable bg;
        Drawable mask;
        if (circle) {
            GradientDrawable d = Ui.oval(active ? activeColor : Ui.SCRIM);
            d.setStroke(Ui.dp(c, 1), active ? Ui.withAlpha(0xFFFFFF, 90) : Ui.BORDER);
            bg = d;
            mask = Ui.oval(0xFFFFFFFF);
        } else {
            GradientDrawable d = active
                    ? Ui.gradient(c, activeColor, Ui.withAlpha(activeColor, 150), radiusDp, GradientDrawable.Orientation.TL_BR)
                    : Ui.glass(c, radiusDp);
            if (active) d.setStroke(Ui.dp(c, 1), Ui.withAlpha(0xFFFFFF, 60));
            bg = d;
            mask = Ui.round(0xFFFFFFFF, Ui.dp(c, radiusDp));
        }
        setBackground(Ui.ripple(bg, mask));
        icon.setImageDrawable(Ui.icon(c, iconRes, active ? (circle && activeColor == Ui.YELLOW ? 0xFF1A1300 : Ui.TEXT) : Ui.MUTED));
        label.setTextColor(active ? Ui.TEXT : Ui.MUTED);
        setElevation(active ? Ui.dp(c, 6) : 0);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            setOutlineAmbientShadowColor(activeColor);
            setOutlineSpotShadowColor(activeColor);
        }
    }

    /** Turns the tile into a hold-to-run control (pressed look while held). */
    void onHold(final OnHold action) {
        setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    setActive(true);
                    action.hold(true);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    setActive(false);
                    action.hold(false);
                    return true;
            }
            return true;
        });
    }
}
