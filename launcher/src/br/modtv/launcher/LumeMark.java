package br.modtv.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.text.TextPaint;
import android.view.View;

/** Marca "lume": Sora 600 em minúsculas (tracking -0,05em) e uma gota de vidro âmbar no lugar do ponto. */
final class LumeMark extends View {

    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint drop = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float glow = Lume.f(14), dot = Lume.f(18), gap = Lume.f(5);

    LumeMark(Context c, float sizeDesign) {
        super(c);
        text.setTypeface(Lume.sora600);
        text.setTextSize(Lume.f(sizeDesign));
        text.setLetterSpacing(-0.05f);
        text.setColor(Lume.NEVOA);
        setContentDescription("lume");
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        Paint.FontMetrics fm = text.getFontMetrics();
        int w = Math.round(text.measureText("lume") + gap + dot + 2 * glow);
        int h = Math.round(fm.descent - fm.ascent + 2 * glow);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas c) {
        float base = glow - text.getFontMetrics().ascent;
        c.drawText("lume", glow, base, text);
        float cx = glow + text.measureText("lume") + gap + dot / 2, cy = base - dot / 2;
        drop.setShader(new RadialGradient(cx - dot * 0.22f, cy - dot * 0.26f, dot * 0.72f,
                new int[]{Color.WHITE, 0xFFFFE2B8, Lume.AMBAR, 0xFF8A4A08},
                new float[]{0f, 0.22f, 0.62f, 1f}, Shader.TileMode.CLAMP));
        halo.setShader(new RadialGradient(cx, cy, dot / 2 + glow,
                new int[]{Lume.alpha(Lume.AMBAR, 0.55f), Lume.alpha(Lume.AMBAR, 0.18f), Color.TRANSPARENT},
                new float[]{0.3f, 0.6f, 1f}, Shader.TileMode.CLAMP)); // brilho âmbar em volta da gota
        c.drawCircle(cx, cy, dot / 2 + glow, halo);
        c.drawCircle(cx, cy, dot / 2, drop);
    }
}
