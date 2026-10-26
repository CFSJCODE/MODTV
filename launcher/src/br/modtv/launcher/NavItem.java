package br.modtv.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/** Item do menu lateral: ícone de traço e texto de 24 px; o ativo é uma pílula de vidro (cantos de 20). */
final class NavItem extends LumeView {

    static final float MARGIN = 8f;

    private final String label;
    private final int glyph;
    private boolean selected;

    NavItem(Context c, String label, int glyph) {
        super(c, MARGIN);
        this.label = label;
        this.glyph = glyph;
        growTo = 1.06f;
        setContentDescription(label);
        text.setTypeface(Lume.inter500);
        text.setTextSize(Lume.f(24));
    }

    void setActive(boolean on) {
        if (selected != on) {
            selected = on;
            setSelected(on);
            invalidate();
        }
    }

    @Override
    protected float radius(RectF b) {
        return Lume.f(20);
    }

    @Override
    protected boolean castsShadow() {
        return selected; // a pílula só existe no item ativo
    }

    @Override
    protected void drawSurface(Canvas c, RectF b) {
        glass.paint(c, this, b, Lume.f(20), focus, sweep, GlassPainter.CLEAR, selected);
        float t = Math.max(selected ? 1f : 0f, focus);
        int col = GlassPainter.blend(Lume.SEC2, Lume.NEVOA, t);
        float is = Lume.f(28), x = b.left + Lume.f(20);
        Icons.draw(c, glyph, x, b.centerY() - is / 2, is, selected ? Lume.AMBAR : col, icon);
        text.setColor(col);
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText(label, x + is + Lume.f(18), b.centerY() - (text.descent() + text.ascent()) / 2, text);
    }
}
