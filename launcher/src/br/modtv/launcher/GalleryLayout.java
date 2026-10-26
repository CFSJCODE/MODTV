package br.modtv.launcher;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.widget.FrameLayout;

/**
 * A galeria montada em camadas: plano de vídeo (fotos 4K) atrás, a galeria (fotos JPEG, legenda,
 * relógio, brilho reduzido) no meio e uma camada preta por cima para a transição pelo preto.
 */
final class GalleryLayout extends FrameLayout {

    final GalleryView gallery;
    private final VideoPlane plane;

    GalleryLayout(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
        plane = new VideoPlane(c);
        addView(plane.view, new LayoutParams(-1, -1));
        View black = new View(c);
        black.setBackgroundColor(Color.BLACK);
        gallery = new GalleryView(c, plane, black);
        addView(gallery, new LayoutParams(-1, -1));
        addView(black, new LayoutParams(-1, -1));
    }
}
