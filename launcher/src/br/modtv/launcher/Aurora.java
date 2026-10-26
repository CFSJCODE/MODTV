package br.modtv.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;

/**
 * Cena de luzes em gradientes radiais (o fundo da tela e a "arte" do destaque) e a versão desfocada
 * dela, que o vidro usa. Estática: pintada e desfocada uma vez. Tempo real: as luzes derivam e a
 * cena é repintada em resolução reduzida, com buffers reaproveitados (sem alocar por quadro).
 */
final class Aurora {

    final int w, h;
    /** Cena e desfoque atuais, e quantos px de destino cada px deles cobre. */
    Bitmap sharp, blur;
    float sharpScale, blurScale;

    private final int base;
    private final float[][] lights; // x, y (fração de w/h), raio (fração de w), cor RGB, alfa, fase
    private final float[][] rings;  // x, y, raio (fração de w), alfa
    private final int liveDiv;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint draw = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect dst = new Rect();

    private final Bitmap staticSharp, staticBlur;
    private Bitmap liveSharp, liveSmall, liveBlur;
    private Canvas liveCanvas, smallCanvas;
    private int[] bufA, bufB;

    Aurora(int base, float[][] lights, float[][] rings, int w, int h, int sharpDiv, int liveDiv) {
        this.base = base;
        this.lights = lights;
        this.rings = rings;
        this.w = Math.max(8, w);
        this.h = Math.max(8, h);
        this.liveDiv = liveDiv;
        staticSharp = Bitmap.createBitmap(this.w / sharpDiv, this.h / sharpDiv, Bitmap.Config.ARGB_8888);
        paintScene(new Canvas(staticSharp), staticSharp.getWidth(), staticSharp.getHeight(), 0f);
        int bw = this.w / 8, bh = this.h / 8;
        staticBlur = Bitmap.createScaledBitmap(staticSharp, bw, bh, true);
        int[] a = new int[bw * bh], b = new int[bw * bh];
        staticBlur.getPixels(a, 0, bw, 0, 0, bw, bh);
        boxBlur(a, b, bw, bh, Math.max(2, bw / 70), 3);
        staticBlur.setPixels(a, 0, bw, 0, 0, bw, bh);
        useStatic();
    }

    /** Volta à cena estática e solta os buffers do modo tempo real. */
    void useStatic() {
        set(staticSharp, staticBlur);
        liveSharp = liveSmall = liveBlur = null;
        liveCanvas = smallCanvas = null;
        bufA = bufB = null;
    }

    /** Repinta a cena no instante {@code t} (s) nos buffers do modo tempo real. */
    void renderLive(float t) {
        if (liveSharp == null) {
            liveSharp = Bitmap.createBitmap(w / liveDiv, h / liveDiv, Bitmap.Config.ARGB_8888);
            liveSmall = Bitmap.createBitmap(w / 8, h / 8, Bitmap.Config.ARGB_8888);
            liveBlur = Bitmap.createBitmap(w / 8, h / 8, Bitmap.Config.ARGB_8888);
            liveCanvas = new Canvas(liveSharp);
            smallCanvas = new Canvas(liveSmall);
            bufA = new int[(w / 8) * (h / 8)];
            bufB = new int[(w / 8) * (h / 8)];
        }
        paintScene(liveCanvas, liveSharp.getWidth(), liveSharp.getHeight(), t);
        int sw = liveSmall.getWidth(), sh = liveSmall.getHeight();
        dst.set(0, 0, sw, sh);
        smallCanvas.drawBitmap(liveSharp, null, dst, draw);
        liveSmall.getPixels(bufA, 0, sw, 0, 0, sw, sh);
        boxBlur(bufA, bufB, sw, sh, 3, 2);
        liveBlur.setPixels(bufA, 0, sw, 0, 0, sw, sh);
        set(liveSharp, liveBlur);
    }

    private void set(Bitmap s, Bitmap b) {
        sharp = s;
        blur = b;
        sharpScale = (float) w / s.getWidth();
        blurScale = (float) w / b.getWidth();
    }

    private void paintScene(Canvas c, int sw, int sh, float t) {
        c.drawColor(base);
        paint.setStyle(Paint.Style.FILL);
        for (float[] l : lights) {
            float ph = l[5];
            float x = (l[0] + 0.05f * (float) Math.sin(t * 0.21f + ph)) * sw;
            float y = (l[1] + 0.07f * (float) Math.cos(t * 0.17f + ph * 1.3f)) * sh;
            float r = l[2] * (1f + 0.08f * (float) Math.sin(t * 0.31f + ph)) * sw;
            int col = (int) l[3];
            paint.setShader(new RadialGradient(x, y, Math.max(1f, r),
                    new int[]{Color.argb((int) l[4], (col >> 16) & 255, (col >> 8) & 255, col & 255),
                            Color.argb((int) (l[4] * 0.45f), (col >> 16) & 255, (col >> 8) & 255, col & 255),
                            Color.TRANSPARENT},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, sw, sh, paint);
        }
        paint.setShader(null);
        if (rings != null) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, 2f * Lume.u * sw / w));
            for (float[] r : rings) {
                paint.setColor(Color.argb((int) r[3], 255, 236, 214));
                float drift = 0.01f * (float) Math.sin(t * 0.25f);
                c.drawCircle((r[0] + drift) * sw, r[1] * sh, r[2] * sw, paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }
    }

    /** Desfoque em caixa separável, repetido n vezes (aproxima um gaussiano). Resultado em {@code a}. */
    static void boxBlur(int[] a, int[] b, int w, int h, int radius, int passes) {
        for (int i = 0; i < passes; i++) {
            blurLine(a, b, w, h, radius, true);
            blurLine(b, a, w, h, radius, false);
        }
    }

    private static void blurLine(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h, div = 2 * r + 1;
        for (int l = 0; l < lines; l++) {
            int sr = 0, sg = 0, sb = 0;
            for (int k = -r; k <= r; k++) {
                int px = in[idx(l, Math.min(len - 1, Math.max(0, k)), w, horizontal)];
                sr += (px >> 16) & 255;
                sg += (px >> 8) & 255;
                sb += px & 255;
            }
            for (int i = 0; i < len; i++) {
                out[idx(l, i, w, horizontal)] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int add = in[idx(l, Math.min(len - 1, i + r + 1), w, horizontal)];
                int sub = in[idx(l, Math.max(0, i - r), w, horizontal)];
                sr += ((add >> 16) & 255) - ((sub >> 16) & 255);
                sg += ((add >> 8) & 255) - ((sub >> 8) & 255);
                sb += (add & 255) - (sub & 255);
            }
        }
    }

    private static int idx(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }
}
