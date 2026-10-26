package br.modtv.launcher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;

/**
 * Base dos elementos focáveis de vidro: anima o foco (entrada 250 ms, saída 150 ms, curvas Fluent),
 * passa o realce uma vez (380 ms) e escala a superfície em volta do centro. Em repouso não anima.
 * Cada View reserva {@link #margin} em volta da superfície para crescer sem ser cortada.
 */
abstract class LumeView extends View implements Lume.Glassy {

    protected final GlassPainter glass = new GlassPainter();
    protected final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    protected final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final RectF box = new RectF();
    protected float focus;
    protected float sweep = -1f;
    protected float growTo = 1.07f;
    protected float margin;
    private ValueAnimator focusAnim, sweepAnim;

    private final RectF outlineBox = new RectF();
    private float shadowState = Float.NaN; // último estado aplicado à sombra (evita refazer o contorno)

    LumeView(Context c, float marginDesignPx) {
        super(c);
        margin = Lume.f(marginDesignPx);
        setFocusable(true);
        setClickable(true);
        setDefaultFocusHighlightEnabled(false); // o foco é desenhado pela própria superfície
        // Sombra suave nativa (contorno + elevação): desenhada pelo RenderThread, sem desfoque por quadro.
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                restBox(outlineBox);
                float s = 1f + (growTo - 1f) * focus;
                float hw = outlineBox.width() * s / 2, hh = outlineBox.height() * s / 2;
                float cx = outlineBox.centerX(), cy = outlineBox.centerY();
                int l = Math.round(cx - hw), t = Math.round(cy - hh), r = Math.round(cx + hw), b = Math.round(cy + hh);
                // Raio no máximo meia aresta menos 1 px: pílula exata gera geometria degenerada na sombra.
                float rad = Math.min(radius(outlineBox) * s, Math.min(r - l, b - t) / 2f - 1f);
                o.setRoundRect(l, t, r, b, Math.max(0f, rad));
                o.setAlpha(0.9f);
            }
        });
    }

    /** Raio dos cantos da superfície. */
    protected abstract float radius(RectF box);

    /** A superfície está opaca e deve projetar sombra agora? */
    protected boolean castsShadow() {
        return true;
    }

    private void applyElevation() {
        boolean on = Lume.level >= Lume.MEDIUM && castsShadow();
        float state = on ? focus : -1f;
        if (state == shadowState) return;
        shadowState = state;
        setElevation(on ? Lume.f(8) : 0f); // constante: no foco quem brilha é o halo âmbar
        if (on) invalidateOutline();
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        if (focusAnim != null) focusAnim.cancel();
        focusAnim = ValueAnimator.ofFloat(focus, focused ? 1f : 0f);
        focusAnim.setDuration(focused ? Lume.ENTER_MS : Lume.EXIT_MS);
        focusAnim.setInterpolator(focused ? Lume.ENTER : Lume.EXIT);
        focusAnim.addUpdateListener(a -> {
            focus = (float) a.getAnimatedValue();
            invalidate();
        });
        focusAnim.start();
        if (sweepAnim != null) sweepAnim.cancel();
        sweep = -1f;
        if (focused && Lume.level >= Lume.HIGH) {
            sweepAnim = ValueAnimator.ofFloat(0f, 1f).setDuration(Lume.SHEEN_MS);
            sweepAnim.setInterpolator(Lume.ENTER);
            sweepAnim.addUpdateListener(a -> {
                sweep = (float) a.getAnimatedValue();
                invalidate();
            });
            sweepAnim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator a) {
                    sweep = -1f;
                    invalidate();
                }
            });
            sweepAnim.start();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        // Alguns controles enviam ENTER/NUMPAD_ENTER em vez de DPAD_CENTER.
        if (keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A) {
            return performClick();
        }
        return super.onKeyDown(keyCode, e);
    }

    /** Superfície em repouso (sem escala). */
    protected void restBox(RectF out) {
        out.set(margin, margin, getWidth() - margin, getHeight() - margin);
    }

    @Override
    protected final void onDraw(Canvas c) {
        applyElevation();
        restBox(box);
        float s = 1f + (growTo - 1f) * focus;
        c.save();
        c.scale(s, s, box.centerX(), box.centerY());
        drawSurface(c, box);
        c.restore();
    }

    protected abstract void drawSurface(Canvas c, RectF box);
}
