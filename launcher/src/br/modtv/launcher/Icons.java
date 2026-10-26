package br.modtv.launcher;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Ícones de traço desenhados em uma grade de 24×24 (traço 1,8, pontas arredondadas). */
final class Icons {

    static final int HOME = 0, APPS = 1, INPUT = 2, ART = 3, SETTINGS = 4, MORE = 5, GLASS = 6;

    private static final Path p = new Path();
    private static final RectF r = new RectF();

    private Icons() {
    }

    static void draw(Canvas c, int id, float x, float y, float size, int color, Paint paint) {
        float k = size / 24f;
        c.save();
        c.translate(x, y);
        c.scale(k, k);
        paint.setShader(null);
        paint.clearShadowLayer();
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        p.reset();
        switch (id) {
            case HOME:
                p.moveTo(3.5f, 10.5f);
                p.lineTo(12f, 3.5f);
                p.lineTo(20.5f, 10.5f);
                p.lineTo(20.5f, 20.5f);
                p.lineTo(14.5f, 20.5f);
                p.lineTo(14.5f, 14.5f);
                p.lineTo(9.5f, 14.5f);
                p.lineTo(9.5f, 20.5f);
                p.lineTo(3.5f, 20.5f);
                p.close();
                break;
            case APPS:
                rr(3.5f, 3.5f, 10.5f, 10.5f, 2f);
                rr(13.5f, 3.5f, 20.5f, 10.5f, 2f);
                rr(3.5f, 13.5f, 10.5f, 20.5f, 2f);
                rr(13.5f, 13.5f, 20.5f, 20.5f, 2f);
                break;
            case INPUT: // conector HDMI com cabo
                p.moveTo(3f, 6.5f);
                p.lineTo(21f, 6.5f);
                p.lineTo(21f, 11f);
                p.lineTo(18f, 14f);
                p.lineTo(6f, 14f);
                p.lineTo(3f, 11f);
                p.close();
                p.moveTo(7.5f, 10f);
                p.lineTo(16.5f, 10f);
                p.moveTo(12f, 14f);
                p.lineTo(12f, 20.5f);
                break;
            case ART: // moldura com paisagem
                rr(3f, 4f, 21f, 20f, 2.2f);
                p.moveTo(3.5f, 17f);
                p.lineTo(9f, 11.5f);
                p.lineTo(13f, 15.5f);
                p.lineTo(15.5f, 13f);
                p.lineTo(20.5f, 18f);
                p.addCircle(15.5f, 8.5f, 1.6f, Path.Direction.CW);
                break;
            case SETTINGS: // controles deslizantes
                p.moveTo(3.5f, 7.5f);
                p.lineTo(6.5f, 7.5f);
                p.addCircle(9f, 7.5f, 2.5f, Path.Direction.CW);
                p.moveTo(11.5f, 7.5f);
                p.lineTo(20.5f, 7.5f);
                p.moveTo(3.5f, 16.5f);
                p.lineTo(12.5f, 16.5f);
                p.addCircle(15f, 16.5f, 2.5f, Path.Direction.CW);
                p.moveTo(17.5f, 16.5f);
                p.lineTo(20.5f, 16.5f);
                break;
            case MORE: // grade de pontos
                paint.setStyle(Paint.Style.FILL);
                for (int i = 0; i < 3; i++)
                    for (int j = 0; j < 3; j++) p.addCircle(6f + 6f * i, 6f + 6f * j, 1.5f, Path.Direction.CW);
                break;
            case GLASS: // gota de vidro com reflexo
                p.addCircle(12f, 12f, 8.5f, Path.Direction.CW);
                r.set(7f, 7f, 17f, 17f);
                p.addArc(r, 195f, 75f);
                break;
            default:
                break;
        }
        c.drawPath(p, paint);
        c.restore();
    }

    private static void rr(float l, float t, float rt, float b, float rad) {
        r.set(l, t, rt, b);
        p.addRoundRect(r, rad, rad, Path.Direction.CW);
    }
}
