package br.modtv.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;

/**
 * Paisagens em tela cheia. Esquerda/direita troca a foto, OK mostra ou esconde a legenda, Voltar sai.
 * A tela fica acesa enquanto a galeria está aberta (é a "janela" da TV).
 */
public class PaisagensActivity extends Activity {

    private GalleryView gallery;
    private final BroadcastReceiver timeTick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            gallery.onTimeTick();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        GalleryLayout layout = new GalleryLayout(this);
        gallery = layout.gallery;
        setContentView(layout);
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerReceiver(timeTick, new IntentFilter(Intent.ACTION_TIME_TICK));
        gallery.start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(timeTick);
        gallery.stop();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                gallery.showNext();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                gallery.showPrevious();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                gallery.toggleCaption();
                return true;
            default:
                return super.onKeyDown(keyCode, e);
        }
    }
}
