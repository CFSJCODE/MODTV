package br.modtv.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * Tokens do design "lume": paleta, fontes, escala e movimento. As medidas do design são px de uma
 * tela de 1920×1080; {@link #u} converte para px reais (1 na interface 1080p da TV).
 */
final class Lume {

    static final int BREU = 0xFF0B1018;     // fundo
    static final int NEVOA = 0xFFECF0F0;    // texto
    static final int SEC = 0xFFB9C5D2;      // texto secundário
    static final int SEC2 = 0xFF93A1B3;     // texto terciário
    static final int AMBAR = 0xFFE39A2D;    // destaque
    static final int ANIL = 0xFF3A60BE;     // luz fria
    static final int GRAFITE = 0xFF1A2230;  // superfície opaca (vidro desligado)

    /** Níveis do vidro. LIVE = HIGH com o fundo e a arte animados (vidro recalculado por quadro). */
    static final int OFF = 0, MEDIUM = 1, HIGH = 2, LIVE = 3;
    static int level = HIGH;

    /** Movimento Fluent: entrada 250 ms, saída 150 ms. */
    static final Interpolator ENTER = new PathInterpolator(0.1f, 0.9f, 0.2f, 1f);
    static final Interpolator EXIT = new PathInterpolator(0.7f, 0f, 1f, 0.5f);
    static final int ENTER_MS = 250, EXIT_MS = 150, SHEEN_MS = 380;

    /** px reais por px de design. */
    static float u = 1f;

    static Typeface sora600, sora700, inter400, inter500, inter600;

    /** Fundo da tela desfocado (fonte do vidro) e quantos px de tela cada px dele cobre. */
    static Bitmap windowBlur;
    static float windowBlurScale = 8f;

    private Lume() {
    }

    static void init(Context c) {
        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        u = Math.max(dm.widthPixels, dm.heightPixels) / 1920f;
        if (sora600 != null) return;
        sora600 = font(c, "sora_600");
        sora700 = font(c, "sora_700");
        inter400 = font(c, "inter_400");
        inter500 = font(c, "inter_500");
        inter600 = font(c, "inter_600");
    }

    private static Typeface font(Context c, String name) {
        try {
            return Typeface.createFromAsset(c.getAssets(), "fonts/" + name + ".ttf");
        } catch (RuntimeException e) {
            return Typeface.DEFAULT;
        }
    }

    static float f(float designPx) {
        return designPx * u;
    }

    static int px(float designPx) {
        return Math.round(designPx * u);
    }

    static int alpha(int color, float a) {
        return Color.argb(Math.round(Color.alpha(color) * Math.max(0f, Math.min(1f, a))),
                Color.red(color), Color.green(color), Color.blue(color));
    }

    /** Marca as Views que desenham vidro e precisam redesenhar quando o que está atrás muda. */
    interface Glassy {
    }

    /** Invalida todas as superfícies de vidro abaixo de {@code v}. */
    static void invalidateGlass(View v) {
        if (v instanceof Glassy) v.invalidate();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) invalidateGlass(g.getChildAt(i));
        }
    }
}
