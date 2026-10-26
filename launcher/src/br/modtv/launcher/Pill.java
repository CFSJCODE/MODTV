package br.modtv.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * Botão em pílula. NEVOA: fundo Névoa com texto Breu (ação principal e filtro ativo).
 * FROST: pílula de vidro sobre outro vidro. CLEAR: filtro inativo, só o texto até receber foco.
 */
final class Pill extends LumeView {

    private final String label;
    private final float height, pad;
    private int kind;

    Pill(Context c, String label, int kind, float heightDesign, float padDesign, float textDesign, float marginDesign) {
        super(c, marginDesign);
        this.label = label;
        this.kind = kind;
        height = Lume.f(heightDesign);
        pad = Lume.f(padDesign);
        growTo = 1.06f;
        setContentDescription(label);
        text.setTypeface(Lume.inter600);
        text.setTextSize(Lume.f(textDesign));
        text.setTextAlign(Paint.Align.CENTER);
    }

    void setKind(int k) {
        if (kind != k) {
            kind = k;
            setSelected(k == GlassPainter.NEVOA);
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = Math.round(text.measureText(label) + 2 * pad + 2 * margin);
        setMeasuredDimension(w, Math.round(height + 2 * margin));
    }

    @Override
    protected float radius(RectF b) {
        return b.height() / 2;
    }

    @Override
    protected boolean castsShadow() {
        // Sem sombra: a pílula fica dentro do destaque/da pílula dos filtros, que já projetam a sua,
        // e sombra de filho elevado dentro de pai elevado derrubava o renderizador do emulador.
        return false;
    }

    @Override
    protected void drawSurface(Canvas c, RectF b) {
        float r = b.height() / 2;
        glass.paint(c, this, b, r, focus, sweep, kind, false);
        if (kind == GlassPainter.NEVOA) text.setColor(Lume.BREU);
        else if (kind == GlassPainter.CLEAR) text.setColor(GlassPainter.blend(Lume.SEC, Lume.NEVOA, focus));
        else text.setColor(Lume.NEVOA);
        c.drawText(label, b.centerX(), b.centerY() - (text.descent() + text.ascent()) / 2, text);
    }
}
