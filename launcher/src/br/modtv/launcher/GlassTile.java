package br.modtv.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;

/**
 * Cartão de vidro (cantos de 28): ícone no alto à esquerda e nome embaixo. Só redesenha em foco,
 * rolagem ou troca de nível; em repouso não gera quadros.
 */
public class GlassTile extends LumeView {

    /** Margem reservada em volta do cartão (escala do foco + anel). */
    static final float MARGIN = 16f;

    private final String label;
    private final String sub;
    private final Drawable art;
    private final int glyph;
    private final boolean dim;
    private final RectF well = new RectF();
    private final Paint wellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /**
     * @param art   ícone do app (ou null)
     * @param glyph ícone de traço de {@link Icons} quando não há {@code art} (-1 = inicial do nome)
     * @param sub   linha secundária (ou null)
     * @param dim   app ausente: conteúdo esmaecido
     */
    GlassTile(Context c, String label, Drawable art, int glyph, String sub, boolean dim) {
        super(c, MARGIN);
        this.label = label;
        this.art = art;
        this.glyph = glyph;
        this.sub = sub;
        this.dim = dim;
        setContentDescription(sub == null ? label : label + ", " + sub);
        text.setTextAlign(Paint.Align.LEFT);
    }

    @Override
    protected float radius(RectF b) {
        return Lume.f(28);
    }

    @Override
    protected void drawSurface(Canvas c, RectF b) {
        float r = Lume.f(28);
        glass.paint(c, this, b, r, focus, sweep, GlassPainter.GLASS, false);

        float pad = Lume.f(22), s = Lume.f(56);
        float alpha = dim ? 0.55f : 1f;
        well.set(b.left + pad, b.top + pad, b.left + pad + s, b.top + pad + s);
        if (art != null) {
            art.setBounds(Math.round(well.left), Math.round(well.top), Math.round(well.right), Math.round(well.bottom));
            art.setAlpha(Math.round(255 * alpha));
            art.draw(c);
        } else {
            wellPaint.setColor(Color.argb(Math.round(22 + 14 * focus), 255, 255, 255));
            c.drawRoundRect(well, Lume.f(18), Lume.f(18), wellPaint);
            int col = Lume.alpha(focus > 0 ? GlassPainter.blend(Lume.NEVOA, Lume.AMBAR, focus * 0.6f) : Lume.NEVOA, alpha);
            if (glyph >= 0) {
                float is = Lume.f(30);
                Icons.draw(c, glyph, well.centerX() - is / 2, well.centerY() - is / 2, is, col, icon);
            } else {
                text.setTypeface(Lume.sora600);
                text.setTextSize(Lume.f(26));
                text.setColor(col);
                text.setTextAlign(Paint.Align.CENTER);
                String ini = label.isEmpty() ? "?" : label.substring(0, 1).toUpperCase();
                c.drawText(ini, well.centerX(), well.centerY() - (text.descent() + text.ascent()) / 2, text);
                text.setTextAlign(Paint.Align.LEFT);
            }
        }

        float maxW = b.width() - 2 * pad;
        float base = b.bottom - pad - Lume.f(2);
        if (sub != null) {
            text.setTypeface(Lume.inter400);
            text.setTextSize(Lume.f(20));
            text.setColor(Lume.SEC2);
            CharSequence t = TextUtils.ellipsize(sub, text, maxW, TextUtils.TruncateAt.END);
            c.drawText(t, 0, t.length(), b.left + pad, base, text);
            base -= Lume.f(28);
        }
        text.setTypeface(Lume.inter500);
        text.setTextSize(Lume.f(22));
        text.setColor(Lume.alpha(Lume.NEVOA, dim ? 0.7f : 1f));
        CharSequence t = TextUtils.ellipsize(label, text, maxW, TextUtils.TruncateAt.END);
        c.drawText(t, 0, t.length(), b.left + pad, base, text);
    }
}
