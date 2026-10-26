package br.modtv.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Coleção de paisagens, fora do APK: a pasta do app no armazenamento da TV
 * (/sdcard/Android/data/br.modtv.launcher/files/paisagens/, sem permissão, recebe adb push) somada a
 * /sdcard/lume/paisagens/ (pen drive; só se READ_EXTERNAL_STORAGE tiver sido concedido por pm grant).
 * Cada foto é um JPEG; um .mp4 de mesmo nome (clipe HEVC 4K parado) faz a foto sair em 4K pelo plano
 * de vídeo. Cada pasta pode ter um paisagens.json com título e lugar; sem legenda, vale o nome do arquivo.
 */
final class Photos {

    /** Pasta na TV para fotos do usuário. */
    static final File USER_DIR = new File(Environment.getExternalStorageDirectory(), "lume/paisagens");

    static final class Photo {
        final File file;  // JPEG
        final File clip;  // clipe HEVC 4K de mesmo nome, ou null (aí vale o JPEG, em 1080p)
        final String title, place;

        Photo(File file, File clip, String title, String place) {
            this.file = file;
            this.clip = clip;
            this.title = title;
            this.place = place;
        }

        /** "Título · Lugar" (ou só o título). */
        String caption() {
            return place == null || place.isEmpty() ? title : title + " · " + place;
        }

        String key() {
            return file.getName() + ":" + file.length();
        }

        InputStream open(Context c) throws IOException {
            return new FileInputStream(file);
        }
    }

    private static List<Photo> cache;

    private Photos() {
    }

    /** Recarrega a lista (fotos do usuário podem ter mudado). */
    static synchronized List<Photo> reload(Context c) {
        cache = null;
        return list(c);
    }

    static synchronized List<Photo> list(Context c) {
        if (cache != null) return cache;
        List<Photo> out = new ArrayList<>();
        addDir(out, c.getExternalFilesDir("paisagens"));
        addDir(out, USER_DIR);
        cache = out;
        return out;
    }

    private static void addDir(List<Photo> out, File dir) {
        File[] files;
        try {
            files = dir == null ? null : dir.listFiles();
        } catch (SecurityException e) {
            files = null;
        }
        if (files == null) return;
        Arrays.sort(files);
        Map<String, String[]> caps = captions(readFile(new File(dir, "paisagens.json")));
        for (File f : files) {
            if (!f.isFile() || !isImage(f.getName())) continue;
            File clip = new File(dir, f.getName().replaceFirst("\\.[^.]+$", "") + ".mp4");
            out.add(photo(f, clip.isFile() ? clip : null, f.getName(), caps));
        }
    }

    static int count(Context c) {
        return list(c).size();
    }

    static Photo get(Context c, int i) {
        List<Photo> l = list(c);
        return l.isEmpty() ? null : l.get(Math.floorMod(i, l.size()));
    }

    private static Photo photo(File file, File clip, String name, Map<String, String[]> caps) {
        String[] cap = caps.get(name);
        if (cap != null) return new Photo(file, clip, cap[0], cap[1]);
        String base = name.replaceFirst("\\.[^.]+$", "").replace('_', ' ').replace('-', ' ').trim();
        String title = base.isEmpty() ? name : base.substring(0, 1).toUpperCase(Locale.ROOT) + base.substring(1);
        return new Photo(file, clip, title, null);
    }

    private static boolean isImage(String n) {
        String l = n.toLowerCase(Locale.ROOT);
        return l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png") || l.endsWith(".webp");
    }

    /** paisagens.json: lista de {"arquivo", "titulo", "lugar", "fotografo", "fonte"}; aqui só a legenda importa. */
    private static Map<String, String[]> captions(String json) {
        Map<String, String[]> m = new HashMap<>();
        if (json == null) return m;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                m.put(o.optString("arquivo"), new String[]{o.optString("titulo", o.optString("arquivo")), o.optString("lugar", "")});
            }
        } catch (Exception ignored) {
            // JSON inválido: usa os nomes dos arquivos.
        }
        return m;
    }

    private static String readFile(File f) {
        if (!f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            return read(in);
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Decodifica reduzindo (potência de 2) até caber em reqW×reqH; ARGB_8888. Chamar fora da UI. */
    static Bitmap decode(Context c, Photo p, int reqW, int reqH) {
        if (p == null) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = p.open(c)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            if (o.outWidth <= 0) return null;
            int s = 1;
            while (o.outWidth / (s * 2) >= reqW && o.outHeight / (s * 2) >= reqH) s *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = s;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream in = p.open(c)) {
                return BitmapFactory.decodeStream(in, null, o);
            }
        } catch (IOException | SecurityException | OutOfMemoryError e) {
            return null;
        }
    }

    /** Miniatura de 320 px (recorte central 16:9), gerada uma vez e guardada no cache do app. */
    static Bitmap thumb(Context c, Photo p) {
        File f = new File(c.getCacheDir(), "thumbs/" + Integer.toHexString(p.key().hashCode()) + ".jpg");
        if (f.isFile()) {
            Bitmap b = BitmapFactory.decodeFile(f.getPath());
            if (b != null) return b;
        }
        Bitmap full = decode(c, p, 640, 360);
        if (full == null) return null;
        Bitmap t = cover(full, 320, 180, 0.5f);
        full.recycle();
        File dir = f.getParentFile();
        if (dir != null && (dir.isDirectory() || dir.mkdirs())) {
            try (FileOutputStream out = new FileOutputStream(f)) {
                t.compress(Bitmap.CompressFormat.JPEG, 85, out);
            } catch (IOException ignored) {
            }
        }
        return t;
    }

    /** Recorte que preenche w×h (como centerCrop), com o foco vertical em {@code fy} (0..1). */
    static Bitmap cover(Bitmap src, int w, int h, float fy) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        float scale = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
        int cw = Math.round(w / scale), ch = Math.round(h / scale);
        int left = (src.getWidth() - cw) / 2;
        int top = Math.round((src.getHeight() - ch) * fy);
        new Canvas(out).drawBitmap(src, new Rect(left, top, left + cw, top + ch), new Rect(0, 0, w, h),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    /** Versão desfocada (1/8 da resolução) para o vidro. */
    static Bitmap blur(Bitmap sharp) {
        int bw = Math.max(1, sharp.getWidth() / 8), bh = Math.max(1, sharp.getHeight() / 8);
        Bitmap b = Bitmap.createScaledBitmap(sharp, bw, bh, true);
        if (!b.isMutable()) b = b.copy(Bitmap.Config.ARGB_8888, true);
        int[] px = new int[bw * bh], tmp = new int[bw * bh];
        b.getPixels(px, 0, bw, 0, 0, bw, bh);
        Aurora.boxBlur(px, tmp, bw, bh, Math.max(2, bw / 70), 3);
        b.setPixels(px, 0, bw, 0, 0, bw, bh);
        return b;
    }
}
