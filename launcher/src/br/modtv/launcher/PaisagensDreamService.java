package br.modtv.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.service.dreams.DreamService;

/** Protetor de tela "lume · Paisagens": a mesma galeria, sem interação. Para tudo ao acordar. */
public class PaisagensDreamService extends DreamService {

    private GalleryView gallery;
    private boolean ticking;
    private final BroadcastReceiver timeTick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (gallery != null) gallery.onTimeTick();
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        GalleryLayout layout = new GalleryLayout(this);
        gallery = layout.gallery;
        setContentView(layout);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        registerReceiver(timeTick, new IntentFilter(Intent.ACTION_TIME_TICK));
        ticking = true;
        gallery.start();
    }

    @Override
    public void onDreamingStopped() {
        super.onDreamingStopped();
        if (ticking) unregisterReceiver(timeTick);
        ticking = false;
        if (gallery != null) gallery.release();
    }
}
