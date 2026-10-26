package br.modtv.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.LinearLayout;

/** Contêiner horizontal em pílula de vidro (ex.: os filtros do topo). Não recebe foco. */
final class GlassRow extends LinearLayout implements Lume.Glassy {

    private final GlassPainter glass = new GlassPainter();
    private final RectF box = new RectF();

    GlassRow(Context c) {
        super(c);
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Math.max(0f, v.getHeight() / 2f - 1f));
                o.setAlpha(0.9f);
            }
        });
    }

    @Override
    protected void onDraw(Canvas c) {
        setElevation(Lume.level >= Lume.MEDIUM ? Lume.f(8) : 0f);
        box.set(0, 0, getWidth(), getHeight());
        glass.paint(c, this, box, box.height() / 2, 0f, -1f, GlassPainter.GLASS, false);
    }
}
