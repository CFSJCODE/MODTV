package br.modtv.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * Desenha uma superfície de vidro lume (Liquid Glass + Fluent).
 * Repouso: recorte do fundo desfocado atrás da superfície, branco 7%, borda branca 16%, linha de luz
 * no topo (branco 28%). Foco: borda âmbar de 2 px, anel âmbar 20% e o
 * realce que passa uma vez. Escala, sombra suave e brilho âmbar ficam com {@link LumeView}.
 */
final class GlassPainter {

    /** GLASS: vidro sobre o fundo. CLEAR: só aparece selecionado ou em foco. NEVOA: pílula sólida.
     *  FROST: vidro translúcido sem amostra (sobre outro vidro). */
    static final int GLASS = 0, CLEAR = 1, NEVOA = 2, FROST = 3;
    private static final float[] GLOW = {0.16f, 0.09f, 0.045f, 0.018f};

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private Bitmap shaderBitmap;
    private final RectF tmp = new RectF();
    private final int[] loc = new int[2];

    GlassPainter() {
        stroke.setStyle(Paint.Style.STROKE);
    }

    /**
     * @param box   retângulo em coordenadas da View (sem a escala do foco, que pode estar no canvas)
     * @param on    selecionado (CLEAR) / ativo
     * @param sweep posição do realce (0..1) ou -1
     */
    void paint(Canvas c, View v, RectF box, float r, float focus, float sweep, int kind, boolean on) {
        int lvl = Lume.level;
        float a = kind == CLEAR ? Math.max(on ? 1f : 0f, focus) : 1f;
        // A sombra suave é nativa (elevação, ver LumeView); setShadowLayer custava segundos por quadro.
        if (a > 0f) {
            // Camadas em drawRoundRect (sem clipPath: a máscara de recorte por desenho é cara na GPU).
            fill.setShader(null);
            if (kind == NEVOA) {
                fill.setColor(focus > 0f ? blend(Lume.NEVOA, Color.WHITE, focus) : Lume.NEVOA);
                c.drawRoundRect(box, r, r, fill);
            } else {
                if (kind == FROST) {
                    fill.setColor(Color.argb(Math.round(26 + 22 * focus), 255, 255, 255));
                    c.drawRoundRect(box, r, r, fill);
                } else {
                    Bitmap blur = Lume.windowBlur;
                    if (lvl >= Lume.MEDIUM && blur != null) {
                        // O fundo desfocado vira textura da própria superfície, alinhada à janela.
                        if (blur != shaderBitmap) {
                            shaderBitmap = blur;
                            bmp.setShader(new BitmapShader(blur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                        }
                        v.getLocationInWindow(loc);
                        float s = Lume.windowBlurScale;
                        matrix.setScale(s, s);
                        matrix.postTranslate(-loc[0], -loc[1]);
                        bmp.getShader().setLocalMatrix(matrix);
                        bmp.setAlpha(Math.round(255 * a));
                        c.drawRoundRect(box, r, r, bmp);
                    } else {
                        fill.setColor(Lume.alpha(Lume.GRAFITE, a));
                        c.drawRoundRect(box, r, r, fill);
                    }
                    fill.setColor(Color.argb(Math.round((18 + 13 * focus) * a), 255, 255, 255)); // 7% → 12%
                    c.drawRoundRect(box, r, r, fill);
                }
                if (sweep >= 0f && lvl >= Lume.HIGH) {
                    float w = box.width(), x = box.left - w * 0.4f + sweep * w * 1.8f;
                    fill.setShader(new LinearGradient(x - w * 0.22f, box.top, x + w * 0.22f, box.bottom,
                            new int[]{Color.TRANSPARENT, Color.argb(60, 255, 255, 255), Color.TRANSPARENT},
                            null, Shader.TileMode.CLAMP));
                    c.drawRoundRect(box, r, r, fill);
                    fill.setShader(null);
                }
            }

            if (kind != NEVOA) {
                float sw = Lume.f(1.5f);
                tmp.set(box.left + sw / 2, box.top + sw / 2, box.right - sw / 2, box.bottom - sw / 2);
                stroke.setStrokeWidth(sw);
                stroke.setShader(null);
                stroke.setColor(Color.argb(Math.round(41 * a * (1f - focus)), 255, 255, 255)); // borda 16%
                c.drawRoundRect(tmp, r, r, stroke);
                stroke.setShader(new LinearGradient(0, box.top, 0, box.top + Math.max(r, Lume.f(16)),
                        Color.argb(Math.round(72 * a), 255, 255, 255), Color.TRANSPARENT,
                        Shader.TileMode.CLAMP)); // linha de luz 28%
                c.drawRoundRect(tmp, r, r, stroke);
                stroke.setShader(null);
            }
        }

        if (focus > 0f && Lume.level >= Lume.HIGH) {
            // Brilho âmbar: halos concêntricos que esmaecem para fora (sem desfoque, custo de 4 traços).
            float step = Lume.f(5);
            stroke.setShader(null);
            stroke.setStrokeWidth(step);
            for (int i = 0; i < GLOW.length; i++) {
                float o = Lume.f(13) + step * (i + 0.5f);
                tmp.set(box.left - o, box.top - o, box.right + o, box.bottom + o);
                stroke.setColor(Lume.alpha(Lume.AMBAR, GLOW[i] * focus));
                c.drawRoundRect(tmp, r + o, r + o, stroke);
            }
        }
        if (focus > 0f) {
            float ring = Lume.f(6), gap = Lume.f(5);
            tmp.set(box.left - gap - ring / 2, box.top - gap - ring / 2, box.right + gap + ring / 2, box.bottom + gap + ring / 2);
            stroke.setShader(null);
            stroke.setStrokeWidth(ring);
            stroke.setColor(Lume.alpha(Lume.AMBAR, 0.2f * focus)); // anel âmbar 20%
            float rr = r + gap + ring / 2;
            c.drawRoundRect(tmp, rr, rr, stroke);
            float bw = Lume.f(2);
            tmp.set(box.left + bw / 2, box.top + bw / 2, box.right - bw / 2, box.bottom - bw / 2);
            stroke.setStrokeWidth(bw);
            stroke.setColor(Lume.alpha(Lume.AMBAR, focus)); // borda âmbar 2 px
            c.drawRoundRect(tmp, r, r, stroke);
        }
    }

    static int blend(int a, int b, float t) {
        return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }
}
