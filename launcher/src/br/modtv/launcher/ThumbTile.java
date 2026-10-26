package br.modtv.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextUtils;

/** Miniatura de paisagem em cartão de vidro (cantos de 28), com o título embaixo e a marca "Fixada". */
final class ThumbTile extends LumeView {

    private final String label;
    private final boolean fixed;
    private final Paint photo = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private BitmapShader shader;
    private Bitmap thumb;

    ThumbTile(Context c, String label, boolean fixed) {
        super(c, GlassTile.MARGIN);
        this.label = label;
        this.fixed = fixed;
        setContentDescription(fixed ? label + ", fixada" : label);
    }

    /** Chega da thread de fundo, depois de gerada ou lida do cache. */
    void setThumb(Bitmap b) {
        thumb = b;
        shader = b == null ? null : new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        invalidate();
    }

    @Override
    protected float radius(RectF b) {
        return Lume.f(28);
    }

    @Override
    protected void drawSurface(Canvas c, RectF b) {
        float r = Lume.f(28);
        glass.paint(c, this, b, r, focus, sweep, GlassPainter.GLASS, false);
        if (shader != null) {
            float s = Math.max(b.width() / thumb.getWidth(), b.height() / thumb.getHeight());
            matrix.setScale(s, s);
            matrix.postTranslate(b.centerX() - thumb.getWidth() * s / 2, b.centerY() - thumb.getHeight() * s / 2);
            shader.setLocalMatrix(matrix);
            photo.setShader(shader);
            c.drawRoundRect(b, r, r, photo);
        }
        // Escurece a base para o título ler bem.
        shade.setShader(new LinearGradient(0, b.centerY(), 0, b.bottom, Color.TRANSPARENT,
                Lume.alpha(Lume.BREU, 0.75f), Shader.TileMode.CLAMP));
        c.drawRoundRect(b, r, r, shade);
        float pad = Lume.f(20);
        text.setTypeface(Lume.inter500);
        text.setTextSize(Lume.f(22));
        text.setColor(Lume.NEVOA);
        text.setTextAlign(Paint.Align.LEFT);
        CharSequence t = TextUtils.ellipsize(label, text, b.width() - 2 * pad, TextUtils.TruncateAt.END);
        c.drawText(t, 0, t.length(), b.left + pad, b.bottom - pad, text);
        if (fixed) {
            // Pílula âmbar "Fixada" no canto superior esquerdo.
            text.setTypeface(Lume.inter600);
            text.setTextSize(Lume.f(20));
            float tw = text.measureText("Fixada"), ph = Lume.f(34);
            RectF p = new RectF(b.left + Lume.f(14), b.top + Lume.f(14), b.left + Lume.f(14) + tw + Lume.f(28), b.top + Lume.f(14) + ph);
            shade.setShader(null);
            shade.setColor(Lume.AMBAR);
            c.drawRoundRect(p, ph / 2 - 1, ph / 2 - 1, shade);
            text.setColor(Lume.BREU);
            c.drawText("Fixada", p.left + Lume.f(14), p.centerY() - (text.descent() + text.ascent()) / 2, text);
        }
    }
}
