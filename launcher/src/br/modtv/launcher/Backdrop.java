package br.modtv.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.Choreographer;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Fundo Breu com a luz âmbar no canto superior direito e a anil no inferior esquerdo; também é a
 * fonte do desfoque dos cartões de vidro. Estático, não gera quadros. No nível Tempo real as luzes
 * derivam a ~30 quadros/s, junto com as cenas que seguem este fundo (a arte do destaque).
 */
public class Backdrop extends View implements Choreographer.FrameCallback {

    private static final float[][] LIGHTS = {
            {1.00f, -0.05f, 0.55f, 0xE39A2D, 78, 0.0f}, {0.80f, 0.10f, 0.28f, 0xE39A2D, 40, 1.3f},
            {-0.02f, 1.05f, 0.60f, 0x3A60BE, 104, 2.1f}, {0.22f, 0.88f, 0.30f, 0x3A60BE, 44, 3.3f}};
    private static final long FRAME_NS = 33_000_000L;

    private final Runnable onBlurChanged;
    private final Aurora scene;
    private final Paint draw = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect dst = new Rect();
    private final List<Aurora> followers = new ArrayList<>();
    private final List<View> followerViews = new ArrayList<>();
    private boolean live;
    private long startNs, lastNs;

    public Backdrop(Context c, Runnable onBlurChanged) {
        super(c);
        this.onBlurChanged = onBlurChanged;
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        int w = Math.max(dm.widthPixels, dm.heightPixels), h = Math.min(dm.widthPixels, dm.heightPixels);
        // Só gradientes suaves: meia resolução basta e economiza memória.
        scene = new Aurora(Lume.BREU, LIGHTS, null, w, h, 2, 4);
        publish();
    }

    /** Anima {@code a} junto com o fundo no modo tempo real e redesenha {@code v} a cada quadro. */
    void follow(Aurora a, View v) {
        followers.add(a);
        followerViews.add(v);
    }

    void unfollowAll() {
        for (Aurora a : followers) a.useStatic();
        followers.clear();
        followerViews.clear();
    }

    public void setLive(boolean on) {
        if (on == live) return;
        live = on;
        Choreographer ch = Choreographer.getInstance();
        if (on) {
            startNs = lastNs = 0;
            ch.postFrameCallback(this);
        } else {
            ch.removeFrameCallback(this);
            scene.useStatic();
            for (int i = 0; i < followers.size(); i++) {
                followers.get(i).useStatic();
                followerViews.get(i).invalidate();
            }
            publish();
        }
    }

    private void publish() {
        Lume.windowBlur = scene.blur;
        Lume.windowBlurScale = scene.blurScale;
        invalidate();
        if (onBlurChanged != null) onBlurChanged.run();
    }

    @Override
    public void doFrame(long nowNs) {
        if (!live) return;
        Choreographer.getInstance().postFrameCallback(this);
        if (startNs == 0) startNs = nowNs;
        if (lastNs != 0 && nowNs - lastNs < FRAME_NS) return; // limita a ~30 quadros/s
        lastNs = nowNs;
        float t = (nowNs - startNs) / 1e9f;
        scene.renderLive(t);
        for (int i = 0; i < followers.size(); i++) {
            View v = followerViews.get(i);
            if (!v.isShown()) continue;
            followers.get(i).renderLive(t);
            v.invalidate();
        }
        publish();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Choreographer.getInstance().removeFrameCallback(this);
    }

    @Override
    protected void onDraw(Canvas c) {
        dst.set(0, 0, getWidth(), getHeight());
        c.drawBitmap(scene.sharp, null, dst, draw);
    }
}
