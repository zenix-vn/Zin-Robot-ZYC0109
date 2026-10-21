package com.zin.robot;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.widget.TextView;

/** Design tokens and drawable factories. Palette taken from the app icon: deep navy, electric blue, cyan glow. */
final class Ui {
    static final int BG = 0xFF050A18;
    static final int BG2 = 0xFF0A1633;
    static final int GLASS = 0x14FFFFFF;
    static final int GLASS_HI = 0x26FFFFFF;
    static final int BORDER = 0x1CFFFFFF;
    static final int SCRIM = 0x8C050A18;
    static final int TEXT = 0xFFEAF2FF;
    static final int MUTED = 0xFF8EA2C8;
    static final int ACCENT = 0xFF2F7BFF;
    static final int CYAN = 0xFF35D0FF;
    static final int GREEN = 0xFF22C55E;
    static final int RED = 0xFFFF4D5E;
    static final int RED_DARK = 0xFFC81E3A;
    static final int ORANGE = 0xFFFF9F43;
    static final int YELLOW = 0xFFFFD166;
    static final int PINK = 0xFFFF5FA2;
    static final int VIOLET = 0xFF8B5CF6;

    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);

    private Ui() { }

    static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    static int withAlpha(int color, int alpha) { return (color & 0x00FFFFFF) | (alpha << 24); }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    /** Frosted-glass surface: faint white fill with a hairline border. */
    static GradientDrawable glass(Context c, float radiusDp) {
        GradientDrawable d = round(GLASS, dp(c, radiusDp));
        d.setStroke(dp(c, 1), BORDER);
        return d;
    }

    static GradientDrawable gradient(Context c, int from, int to, float radiusDp, GradientDrawable.Orientation o) {
        GradientDrawable d = new GradientDrawable(o, new int[] { from, to });
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    static GradientDrawable oval(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    /** Adds a touch ripple on top of any background. */
    static Drawable ripple(Drawable content, Drawable mask) {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, mask);
    }

    /** Page background: navy gradient with a soft blue glow at the top. */
    static Drawable pageBackground(Context c) {
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { BG2, BG, BG });
        GradientDrawable glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setColors(new int[] { 0x402F7BFF, 0x002F7BFF });
        glow.setGradientCenter(0.5f, 0f);
        glow.setGradientRadius(c.getResources().getDisplayMetrics().widthPixels * 0.9f);
        return new LayerDrawable(new Drawable[] { base, glow });
    }

    static Drawable icon(Context c, int res, int color) {
        Drawable d = c.getDrawable(res).mutate();
        d.setTint(color);
        return d;
    }

    static TextView text(Context c, String s, float sp, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (tf != null) t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        return t;
    }
}
