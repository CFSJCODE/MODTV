package br.modtv.launcher;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.File;
import java.io.IOException;

/**
 * Mostra uma foto em 4K pelo plano de vídeo: toca o clipe HEVC parado num SurfaceView e pausa no
 * primeiro quadro, para o decodificador ficar parado e o plano segurar a imagem. A interface da TV é
 * limitada a 1080p, mas o vídeo sai em 3840×2160 por fora da GPU (como no YouTube).
 */
final class VideoPlane implements SurfaceHolder.Callback {

    private static final long TIMEOUT_MS = 5000;

    final SurfaceView view;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private boolean surfaceReady;
    private File pending;
    private Runnable onShown, onFail;
    private int token; // descarta callbacks de um clipe anterior

    VideoPlane(Context c) {
        view = new SurfaceView(c);
        view.getHolder().addCallback(this);
    }

    /** Mostra {@code clip}; chama {@code shown} com o primeiro quadro na tela ou {@code fail} se der errado. */
    void show(File clip, Runnable shown, Runnable fail) {
        pending = clip;
        onShown = shown;
        onFail = fail;
        if (surfaceReady) begin();
    }

    /** Solta o decodificador (foto em JPEG, galeria parada ou fechada). */
    void clear() {
        token++;
        pending = null;
        main.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private void begin() {
        File clip = pending;
        pending = null;
        if (clip == null) return;
        final int t = ++token;
        final Runnable shown = onShown, fail = onFail;
        main.removeCallbacksAndMessages(null);
        main.postDelayed(() -> failed(t, fail), TIMEOUT_MS);
        try {
            if (player == null) player = new MediaPlayer();
            else player.reset();
            player.setSurface(view.getHolder().getSurface());
            player.setDataSource(clip.getPath());
            player.setOnPreparedListener(p -> {
                if (t == token) p.start();
            });
            player.setOnInfoListener((p, what, extra) -> {
                if (t == token && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    p.pause(); // segura o quadro: decodificador parado, zero trabalho até a próxima troca
                    main.removeCallbacksAndMessages(null);
                    if (shown != null) shown.run();
                }
                return false;
            });
            player.setOnErrorListener((p, what, extra) -> {
                failed(t, fail);
                return true;
            });
            player.prepareAsync();
        } catch (IOException | IllegalStateException | IllegalArgumentException | SecurityException e) {
            failed(t, fail);
        }
    }

    private void failed(int t, Runnable fail) {
        if (t != token) return;
        clear();
        if (fail != null) fail.run();
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        surfaceReady = true;
        if (pending != null) begin();
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int w, int hgt) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        surfaceReady = false;
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
