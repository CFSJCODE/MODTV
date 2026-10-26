package br.modtv.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

/**
 * Destaque (cantos de 36) com uma foto de paisagem e, por cima, um painel de vidro (cantos de 28)
 * que mostra a foto desfocada atrás dele: o painel pega a cor da paisagem. A troca de foto é um
 * esmaecimento único (entrada Fluent); parado, não gera quadros. O conteúdo do painel são Views filhas.
 */
final class HeroView extends FrameLayout implements Lume.Glassy {

    /** Painel de vidro em coordenadas desta View. */
    final RectF panel = new RectF();

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint photoPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint blurPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF box = new RectF();
    private final RectF tmp = new RectF();

    // Foto atual e a anterior (só durante o esmaecimento).
    private BitmapShader sharp, blur, prevSharp, prevBlur;
    private float blurScale = 8f, prevBlurScale = 8f;
    private float fade = 1f;
    private ValueAnimator fadeAnim;

    HeroView(Context c, int w, int h, float panelWidth) {
        super(c);
        setWillNotDraw(false);
        setClipChildren(false);
        float m = Lume.f(40);
        panel.set(m, m, m + panelWidth, h - m);
        stroke.setStyle(Paint.Style.STROKE);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Lume.f(36));
                o.setAlpha(0.9f);
            }
        });
    }

    /** Troca a foto. {@code photo} já vem recortada no tamanho do destaque; {@code soft} é a versão desfocada. */
    void setPhoto(Bitmap photo, Bitmap soft, boolean animate) {
        prevSharp = sharp;
        prevBlur = blur;
        prevBlurScale = blurScale;
        sharp = new BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        blur = new BitmapShader(soft, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        blurScale = (float) photo.getWidth() / soft.getWidth();
        Matrix m = new Matrix();
        m.setScale(blurScale, blurScale);
        blur.setLocalMatrix(m);
        if (fadeAnim != null) fadeAnim.cancel();
        if (!animate || prevSharp == null) {
            fade = 1f;
            prevSharp = prevBlur = null;
            invalidate();
            return;
        }
        fadeAnim = ValueAnimator.ofFloat(0f, 1f).setDuration(Lume.ENTER_MS * 2);
        fadeAnim.setInterpolator(Lume.ENTER);
        fadeAnim.addUpdateListener(a -> {
            fade = (float) a.getAnimatedValue();
            if (fade >= 1f) prevSharp = prevBlur = null;
            invalidate();
        });
        fadeAnim.start();
    }

    @Override
    protected void onDraw(Canvas c) {
        float R = Lume.f(36), r = Lume.f(28);
        box.set(0, 0, getWidth(), getHeight());
        int lvl = Lume.level;
        setElevation(lvl >= Lume.MEDIUM ? Lume.f(14) : 0f); // sombra nativa (sem desfoque por quadro)

        // Foto e painel como texturas em drawRoundRect (sem clipPath).
        if (sharp == null) {
            fill.setShader(null);
            fill.setColor(Lume.GRAFITE);
            c.drawRoundRect(box, R, R, fill);
        } else {
            if (prevSharp != null) layer(c, prevSharp, box, R, photoPaint, 1f);
            layer(c, sharp, box, R, photoPaint, fade);
        }
        // Sombra no canto inferior direito, para a marca "LUME / 0N" ler bem sobre fotos claras.
        fill.setShader(new LinearGradient(box.right * 0.55f, box.top, box.right, box.bottom,
                Color.TRANSPARENT, Lume.alpha(Lume.BREU, 0.45f), Shader.TileMode.CLAMP));
        c.drawRoundRect(box, R, R, fill);
        fill.setShader(null);

        if (lvl >= Lume.MEDIUM && blur != null) {
            if (prevBlur != null) layer(c, prevBlur, panel, r, blurPaint, 1f);
            layer(c, blur, panel, r, blurPaint, fade);
            fill.setColor(Lume.alpha(Lume.BREU, 0.34f)); // véu para o texto ler bem sobre a foto
            c.drawRoundRect(panel, r, r, fill);
            fill.setColor(Color.argb(18, 255, 255, 255)); // branco 7%
            c.drawRoundRect(panel, r, r, fill);
        } else {
            fill.setColor(Lume.alpha(Lume.GRAFITE, 0.92f));
            c.drawRoundRect(panel, r, r, fill);
        }

        float sw = Lume.f(1.5f);
        tmp.set(panel.left + sw / 2, panel.top + sw / 2, panel.right - sw / 2, panel.bottom - sw / 2);
        stroke.setStrokeWidth(sw);
        stroke.setShader(null);
        stroke.setColor(Color.argb(41, 255, 255, 255));
        c.drawRoundRect(tmp, r, r, stroke);
        stroke.setShader(new LinearGradient(0, panel.top, 0, panel.top + r, Color.argb(72, 255, 255, 255),
                Color.TRANSPARENT, Shader.TileMode.CLAMP));
        c.drawRoundRect(tmp, r, r, stroke);
        stroke.setShader(null);

        tmp.set(box.left + sw / 2, box.top + sw / 2, box.right - sw / 2, box.bottom - sw / 2);
        stroke.setColor(Color.argb(26, 255, 255, 255));
        c.drawRoundRect(tmp, R, R, stroke);
    }

    private static void layer(Canvas c, BitmapShader s, RectF rect, float radius, Paint p, float alpha) {
        p.setShader(s);
        p.setAlpha(Math.round(255 * alpha));
        c.drawRoundRect(rect, radius, radius, p);
    }
}
