package br.modtv.launcher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.View;

import java.io.File;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Galeria de paisagens em tela cheia (usada pela PaisagensActivity e pelo protetor de tela).
 * Uma foto por vez, trocando no intervalo escolhido. Foto com clipe 4K: sai pelo plano de vídeo
 * ({@link VideoPlane}) e a troca é pelo preto (500 ms + 500 ms). Foto só em JPEG: desenhada aqui em
 * 1080p, com esmaecimento de 1,5 s. Entre as trocas não gera quadros (sem Ken Burns); o relógio muda
 * uma vez por minuto. Só dois bitmaps grandes vivos: o atual e o próximo, pré-carregado em thread de
 * fundo; o anterior é reciclado ao fim de cada troca.
 */
final class GalleryView extends View {

    /** Preferências da galeria (compartilhadas com o launcher). */
    static final String PREFS = "modtv";
    static final long[] INTERVALS = {30_000, 60_000, 300_000, 900_000};
    static final String[] INTERVAL_NAMES = {"30 s", "1 min", "5 min", "15 min"};
    static final int[] DIMS = {0, 20, 40};

    private static final int FADE_MS = 1500, BLACK_MS = 500;

    /** Foto pronta para exibir: clipe 4K (ou o bitmap 1080p), versão desfocada (vidro) e a legenda. */
    private static final class Slide {
        final int index;
        final Photos.Photo photo;
        final Bitmap blur;
        final String caption;
        final BitmapShader blurShader;
        Bitmap bmp;  // só no modo JPEG
        File clip;   // só no modo 4K

        Slide(int index, Photos.Photo photo, Bitmap bmp, Bitmap blur, File clip) {
            this.index = index;
            this.photo = photo;
            this.bmp = bmp;
            this.blur = blur;
            this.clip = clip;
            caption = photo.caption();
            blurShader = new BitmapShader(blur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }

        void recycle() {
            if (bmp != null) bmp.recycle();
            blur.recycle();
        }
    }

    private final SharedPreferences prefs;
    private final VideoPlane plane;
    private final View black;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Paint photo = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint captionText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint clockText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF pill = new RectF();

    private Slide current, previous, next;
    private float fade = 1f, captionAlpha = 1f;
    private ValueAnimator fadeAnim, captionAnim;
    private boolean running, captionOn = true;
    private int generation; // descarta carregamentos que chegam depois de stop() ou de outra troca

    private final Runnable advance = this::showNext;

    GalleryView(Context c, VideoPlane plane, View black) {
        super(c);
        this.plane = plane;
        this.black = black;
        Lume.init(c);
        prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        stroke.setStyle(Paint.Style.STROKE);
        captionText.setTypeface(Lume.sora600);
        captionText.setTextSize(Lume.f(28));
        captionText.setColor(Lume.NEVOA);
        clockText.setTypeface(Lume.sora600);
        clockText.setTextSize(Lume.f(42));
        clockText.setColor(Lume.NEVOA);
        setFocusable(false); // transparente: no modo 4K a foto está no plano de vídeo, atrás
    }

    /** Começa pela foto fixada ou pela última vista. */
    void start() {
        if (running) return;
        running = true;
        generation++;
        if (Photos.reload(getContext()).isEmpty()) {
            black.setAlpha(0f); // mostra a instrução de como adicionar fotos
            invalidate();
            return;
        }
        int fixed = fixedIndex(getContext(), prefs);
        load(fixed >= 0 ? fixed : prefs.getInt("gal_index", 0), true);
    }

    /** Para os temporizadores e solta os bitmaps. */
    void stop() {
        running = false;
        generation++;
        main.removeCallbacks(advance);
        if (fadeAnim != null) fadeAnim.cancel();
        black.animate().cancel();
        black.setAlpha(1f);
        plane.clear();
        for (Slide s : new Slide[]{current, previous, next}) if (s != null) s.recycle();
        current = previous = next = null;
        invalidate();
    }

    void release() {
        stop();
        loader.shutdownNow();
    }

    void showNext() {
        if (current == null) return;
        if (next != null && next.index == Math.floorMod(current.index + 1, count())) {
            Slide n = next;
            next = null;
            transition(n);
        } else {
            dropNext();
            load(current.index + 1, false);
        }
    }

    void showPrevious() {
        if (current == null) return;
        dropNext(); // mantém só dois bitmaps vivos
        load(current.index - 1, false);
    }

    void toggleCaption() {
        captionOn = !captionOn;
        if (captionAnim != null) captionAnim.cancel();
        captionAnim = ValueAnimator.ofFloat(captionAlpha, captionOn ? 1f : 0f);
        captionAnim.setDuration(captionOn ? Lume.ENTER_MS : Lume.EXIT_MS);
        captionAnim.setInterpolator(captionOn ? Lume.ENTER : Lume.EXIT);
        captionAnim.addUpdateListener(a -> {
            captionAlpha = (float) a.getAnimatedValue();
            invalidate();
        });
        captionAnim.start();
    }

    /** Relógio: chamado no ACTION_TIME_TICK. */
    void onTimeTick() {
        if (prefs.getBoolean("gal_clock", true)) invalidate();
    }

    private int count() {
        return Math.max(1, Photos.count(getContext()));
    }

    private void dropNext() {
        if (next != null) {
            next.recycle();
            next = null;
        }
    }

    /** Carrega a foto {@code index} em segundo plano e a mostra (sem esmaecer se {@code first}). */
    private void load(int index, boolean first) {
        final int gen = generation;
        final Context c = getContext();
        final int i = Math.floorMod(index, count());
        main.removeCallbacks(advance);
        loader.execute(() -> {
            Slide s = decode(c, i);
            main.post(() -> {
                if (gen != generation || !running) {
                    if (s != null) s.recycle();
                    return;
                }
                if (s == null) return;
                if (first || current == null) swapIn(s);
                else transition(s);
            });
        });
    }

    private Slide decode(Context c, int i) {
        Photos.Photo p = Photos.get(c, i);
        if (p == null) return null;
        if (p.clip != null) {
            // Modo 4K: a imagem vem do clipe; do JPEG só uma versão pequena para o vidro.
            Bitmap small = Photos.decode(c, p, 480, 270);
            if (small != null) {
                Bitmap blur = Photos.blur(small);
                small.recycle();
                return new Slide(i, p, null, blur, p.clip);
            }
        }
        Bitmap b = Photos.decode(c, p, 1920, 1080);
        if (b == null) return null;
        return new Slide(i, p, b, Photos.blur(b), null);
    }

    /** Troca de foto: pelo preto se alguma das duas está no plano de vídeo, senão esmaecimento direto. */
    private void transition(Slide s) {
        if (s.clip == null && (current == null || current.clip == null)) {
            crossfade(s);
            return;
        }
        if (fadeAnim != null) fadeAnim.end();
        black.animate().cancel();
        black.animate().alpha(1f).setDuration(BLACK_MS).setInterpolator(Lume.EXIT)
                .withEndAction(() -> swapIn(s)).start();
    }

    /** Com a tela preta (ou na primeira foto): troca a foto e clareia quando ela estiver na tela. */
    private void swapIn(Slide s) {
        if (!running) {
            s.recycle();
            return;
        }
        Slide old = current;
        current = s;
        if (old != null) old.recycle();
        fade = 1f;
        invalidate();
        if (s.clip != null) {
            black.setAlpha(1f);
            plane.show(s.clip, this::reveal, () -> fallback(s));
        } else {
            plane.clear();
            reveal();
        }
    }

    private void reveal() {
        black.animate().cancel();
        black.animate().alpha(0f).setDuration(black.getAlpha() > 0f ? BLACK_MS : 0).setInterpolator(Lume.ENTER)
                .withEndAction(this::afterShow).start();
    }

    /** O clipe falhou: mostra o JPEG em 1080p. */
    private void fallback(Slide s) {
        final int gen = generation;
        final Context c = getContext();
        loader.execute(() -> {
            Bitmap b = Photos.decode(c, s.photo, 1920, 1080);
            main.post(() -> {
                if (gen != generation || current != s || b == null) {
                    if (b != null) b.recycle();
                    return;
                }
                s.bmp = b;
                s.clip = null;
                invalidate();
                reveal();
            });
        });
    }

    private void crossfade(Slide s) {
        if (fadeAnim != null) fadeAnim.end();
        previous = current;
        current = s;
        fade = 0f;
        fadeAnim = ValueAnimator.ofFloat(0f, 1f).setDuration(FADE_MS);
        fadeAnim.setInterpolator(Lume.ENTER);
        fadeAnim.addUpdateListener(a -> {
            fade = (float) a.getAnimatedValue();
            invalidate();
        });
        fadeAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                if (previous != null) {
                    previous.recycle();
                    previous = null;
                }
                fade = 1f;
                invalidate();
                afterShow();
            }
        });
        fadeAnim.start();
    }

    /** Depois de cada troca: lembra a foto, pré-carrega a próxima e agenda a troca automática. */
    private void afterShow() {
        if (current == null || !running) return;
        prefs.edit().putInt("gal_index", current.index).apply();
        if (fixedIndex(getContext(), prefs) >= 0 || count() < 2) return; // foto fixada: não troca sozinha
        final int gen = generation;
        final Context c = getContext();
        final int want = Math.floorMod(current.index + 1, count());
        if (next == null || next.index != want) {
            dropNext();
            loader.execute(() -> {
                Slide s = decode(c, want);
                main.post(() -> {
                    if (gen != generation || !running || current == null || previous != null
                            || s == null || s.index != Math.floorMod(current.index + 1, count())) {
                        if (s != null) s.recycle();
                        return;
                    }
                    dropNext();
                    next = s;
                });
            });
        }
        main.removeCallbacks(advance);
        main.postDelayed(advance, interval(prefs));
    }

    static long interval(SharedPreferences p) {
        return INTERVALS[Math.min(INTERVALS.length - 1, Math.max(0, p.getInt("gal_interval", 1)))];
    }

    /** Índice da foto fixada na lista atual, ou -1 (modo rotativo). */
    static int fixedIndex(Context c, SharedPreferences p) {
        String key = p.getString("gal_fixed", null);
        if (key == null) return -1;
        java.util.List<Photos.Photo> l = Photos.list(c);
        for (int i = 0; i < l.size(); i++) if (l.get(i).key().equals(key)) return i;
        return -1;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        release();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (previous != null && previous.bmp != null) drawPhoto(c, previous, 255, w, h);
        if (current != null && current.bmp != null) drawPhoto(c, current, Math.round(255 * fade), w, h);
        int dim = DIMS[Math.min(DIMS.length - 1, Math.max(0, prefs.getInt("gal_dim", 0)))];
        if (dim > 0) c.drawColor(Color.argb(Math.round(255 * dim / 100f), 0, 0, 0)); // brilho reduzido
        if (current == null) {
            if (running && Photos.count(getContext()) == 0) drawEmpty(c, w, h);
            return;
        }

        float m = Lume.f(64), ph = Lume.f(64), pad = Lume.f(28);
        if (captionAlpha > 0f && current.caption != null) {
            CharSequence t = TextUtils.ellipsize(current.caption, captionText, w * 0.6f, TextUtils.TruncateAt.END);
            float tw = captionText.measureText(t, 0, t.length());
            pill.set(m, h - m - ph, m + tw + 2 * pad, h - m);
            drawGlass(c, pill, captionAlpha, w, h);
            captionText.setAlpha(Math.round(255 * captionAlpha));
            c.drawText(t, 0, t.length(), pill.left + pad,
                    pill.centerY() - (captionText.descent() + captionText.ascent()) / 2, captionText);
        }
        if (prefs.getBoolean("gal_clock", true)) {
            String time = DateFormat.getTimeFormat(getContext()).format(new Date());
            float tw = clockText.measureText(time), chH = Lume.f(76);
            pill.set(w - m - tw - 2 * pad, m, w - m, m + chH);
            drawGlass(c, pill, 1f, w, h);
            c.drawText(time, pill.left + pad, pill.centerY() - (clockText.descent() + clockText.ascent()) / 2, clockText);
        }
    }

    /** Sem fotos: diz onde colocá-las. */
    private void drawEmpty(Canvas c, int w, int h) {
        c.drawColor(Lume.BREU);
        captionText.setAlpha(255);
        captionText.setTextAlign(Paint.Align.CENTER);
        c.drawText("Nenhuma paisagem por aqui ainda", w / 2f, h / 2f - Lume.f(20), captionText);
        TextPaint small = new TextPaint(captionText);
        small.setTypeface(Lume.inter400);
        small.setTextSize(Lume.f(22));
        small.setColor(Lume.SEC);
        c.drawText("Copie fotos para Android/data/br.modtv.launcher/files/paisagens no armazenamento da TV.",
                w / 2f, h / 2f + Lume.f(28), small);
        captionText.setTextAlign(Paint.Align.LEFT);
    }

    /** Foto em "cover" na tela. */
    private void drawPhoto(Canvas c, Slide s, int alpha, int w, int h) {
        coverMatrix(s.bmp, w, h, 1f);
        photo.setAlpha(alpha);
        c.save();
        c.concat(matrix);
        c.drawBitmap(s.bmp, 0, 0, photo);
        c.restore();
    }

    private void coverMatrix(Bitmap b, int w, int h, float extra) {
        float scale = Math.max((float) w / b.getWidth(), (float) h / b.getHeight()) * extra;
        matrix.setScale(scale, scale);
        matrix.postTranslate((w - b.getWidth() * scale) / 2f, (h - b.getHeight() * scale) / 2f);
    }

    /** Pílula de vidro que mostra a foto desfocada atrás dela. */
    private void drawGlass(Canvas c, RectF r, float alpha, int w, int h) {
        float rad = r.height() / 2f - 1f;
        if (Lume.level >= Lume.MEDIUM) {
            // A versão desfocada cobre a tela com a mesma proporção da foto.
            coverMatrix(current.blur, w, h, 1f);
            current.blurShader.setLocalMatrix(matrix);
            glass.setShader(current.blurShader);
            glass.setAlpha(Math.round(255 * alpha));
            c.drawRoundRect(r, rad, rad, glass);
            fill.setColor(Lume.alpha(Lume.BREU, 0.30f * alpha));
        } else {
            fill.setColor(Lume.alpha(Lume.GRAFITE, 0.85f * alpha));
        }
        fill.setShader(null);
        c.drawRoundRect(r, rad, rad, fill);
        fill.setColor(Color.argb(Math.round(18 * alpha), 255, 255, 255)); // branco 7%
        c.drawRoundRect(r, rad, rad, fill);
        float sw = Lume.f(1.5f);
        stroke.setStrokeWidth(sw);
        stroke.setShader(null);
        stroke.setColor(Color.argb(Math.round(41 * alpha), 255, 255, 255)); // borda 16%
        RectF in = new RectF(r.left + sw / 2, r.top + sw / 2, r.right - sw / 2, r.bottom - sw / 2);
        c.drawRoundRect(in, rad, rad, stroke);
        stroke.setShader(new LinearGradient(0, r.top, 0, r.top + r.height() / 2, Color.argb(Math.round(72 * alpha), 255, 255, 255),
                Color.TRANSPARENT, Shader.TileMode.CLAMP)); // linha de luz 28%
        c.drawRoundRect(in, rad, rad, stroke);
        stroke.setShader(null);
    }
}
