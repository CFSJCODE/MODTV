package br.modtv.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tela inicial "lume": menu lateral, destaque das Paisagens com painel de vidro, fileira de apps
 * principais e as telas Apps, Entradas, Configurações e "Escolher paisagem". Sem rede e sem animação contínua: em
 * repouso não gera quadros (o relógio muda uma vez por minuto pelo ACTION_TIME_TICK).
 */
public class HomeActivity extends Activity {

    private static final int WATCH = 1, LISTEN = 2, ART = 4;
    // Apps principais: pacote, nome exibido, filtro.
    private static final String[][] FAVORITES = {
            {"com.globo.globotv", "Globoplay", "1"},
            {"com.google.android.youtube.tv", "YouTube", "1"},
            {"com.spotify.tv.android", "Spotify", "2"}};
    private static final String[] FILTERS = {"Tudo", "Assistir", "Ouvir", "Arte"};
    private static final int[] FILTER_MASK = {WATCH | LISTEN | ART, WATCH, LISTEN, ART};
    private static final String[] LEVEL_NAMES = {"Desligado", "Médio", "Alto", "Tempo real"};
    // Ordem do cartão "Vidro": Alto → Tempo real → Médio → Desligado → Alto.
    private static final int[] NEXT_LEVEL = {Lume.HIGH, Lume.OFF, Lume.LIVE, Lume.MEDIUM};

    // PICK ("Escolher paisagem") não tem item próprio no menu: acende o item Paisagens.
    private static final int HOME = 0, APPS = 1, INPUTS = 2, PAISAGENS = 3, SETTINGS = 4, PICK = 5;
    private static final String[] NAV = {"Início", "Apps", "Entradas", "Paisagens", "Configurações"};
    private static final int[] NAV_ICONS = {Icons.HOME, Icons.APPS, Icons.INPUT, Icons.ART, Icons.SETTINGS};

    // Grade (px de design): conteúdo de 1488 px, cartões de 228×150 com 24 px entre eles.
    private static final int CONTENT_W = 1488, CARD_W = 228, CARD_H = 150, GAP = 24, COLS = 6;

    private SharedPreferences prefs;
    private int userLevel = Lume.HIGH;
    private int thermalStatus = PowerManager.THERMAL_STATUS_NONE;
    private PowerManager power;
    private Backdrop backdrop;
    private boolean resumed;

    private FrameLayout root, pageHost;
    private final NavItem[] nav = new NavItem[NAV.length];
    private int page = -1;

    private View homePage;
    private HeroView hero;
    private LinearLayout homeRow;
    private final List<View> homeTiles = new ArrayList<>();
    private final List<Integer> homeTags = new ArrayList<>();
    private final Pill[] chips = new Pill[FILTERS.length];
    private final Pill[] heroButtons = new Pill[2];
    private int filter;
    private TextView heroMark;
    private int heroIndex = -1;
    // Fotos e miniaturas são decodificadas fora da thread da interface.
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private TextView clock, date, greeting;
    private final List<TextView> statusViews = new ArrayList<>();

    private final BroadcastReceiver timeTick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            updateClock();
        }
    };
    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            homePage = null;
            int p = page;
            page = -1;
            showPage(p, false);
        }
    };
    private final PowerManager.OnThermalStatusChangedListener thermal = status -> {
        thermalStatus = status;
        applyLevel();
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Lume.init(this);
        prefs = getSharedPreferences("modtv", MODE_PRIVATE);
        userLevel = prefs.getInt("glass", Lume.HIGH);
        power = getSystemService(PowerManager.class);

        root = new FrameLayout(this);
        root.setBackgroundColor(Lume.BREU);
        backdrop = new Backdrop(this, () -> Lume.invalidateGlass(root));
        root.addView(backdrop);

        LinearLayout shell = new LinearLayout(this);
        shell.setClipChildren(false);
        shell.addView(buildSidebar(), new LinearLayout.LayoutParams(Lume.px(320) - 1, -1));
        View divider = new View(this);
        divider.setBackgroundColor(Color.argb(18, 255, 255, 255));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(1, -1);
        dl.topMargin = dl.bottomMargin = Lume.px(48);
        shell.addView(divider, dl);
        pageHost = new FrameLayout(this);
        pageHost.setClipChildren(false);
        shell.addView(pageHost, new LinearLayout.LayoutParams(0, -1, 1));
        root.addView(shell);

        setContentView(root);
        applyLevel();
        showPage(HOME, false);
        updateClock();
        root.post(this::focusHome); // depois do primeiro layout, para o foco não cair no menu

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packagesChanged, pf);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        updateClock();
        registerReceiver(timeTick, new IntentFilter(Intent.ACTION_TIME_TICK));
        if (power != null) {
            thermalStatus = power.getCurrentThermalStatus();
            power.addThermalStatusListener(thermal);
        }
        applyLevel();
        loadHeroPhoto(); // a galeria pode ter mudado a foto atual ou a fixada
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        unregisterReceiver(timeTick);
        if (power != null) power.removeThermalStatusListener(thermal);
        backdrop.setLive(false);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packagesChanged);
        bg.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // HOME pressionado com o launcher aberto: volta ao Início.
        if (page != HOME) showPage(HOME, true);
        focusHome();
    }

    @Override
    public void onBackPressed() {
        // Tela inicial: o Voltar não sai daqui; nas outras telas volta ao Início.
        if (page != HOME) {
            showPage(HOME, true);
            nav[HOME].requestFocus();
        }
    }

    // ---------------------------------------------------------------- nível do vidro

    /** Nível efetivo = escolha do usuário, rebaixada pela temperatura do sistema. */
    private void applyLevel() {
        int lvl = effectiveLevel();
        // O modo tempo real só roda com a tela inicial visível.
        if (backdrop != null) backdrop.setLive(lvl == Lume.LIVE && resumed);
        if (lvl != Lume.level) {
            Lume.level = lvl;
            if (root != null) Lume.invalidateGlass(root);
        }
        updateStatus();
    }

    private int effectiveLevel() {
        int lvl = userLevel;
        if (thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) lvl = Lume.OFF;
        else if (thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) lvl = Math.min(lvl, Lume.MEDIUM);
        return lvl;
    }

    private void cycleLevel(View v) {
        userLevel = NEXT_LEVEL[userLevel];
        prefs.edit().putInt("glass", userLevel).apply();
        applyLevel();
        int p = page;
        page = -1;
        showPage(p, false);
        View again = pageHost.findViewWithTag("glass");
        if (again != null) again.requestFocus(); // o foco fica no cartão "Vidro"
    }

    // ---------------------------------------------------------------- menu lateral

    private View buildSidebar() {
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(Lume.px(20), Lume.px(40), Lume.px(20), Lume.px(52));
        side.setClipChildren(false);
        side.setClipToPadding(false);

        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2);
        ml.leftMargin = Lume.px(18);
        side.addView(new LumeMark(this, 52), ml);

        for (int i = 0; i < NAV.length; i++) {
            NavItem n = new NavItem(this, NAV[i], NAV_ICONS[i]);
            final int target = i;
            n.setOnClickListener(v -> {
                if (target == PAISAGENS) openGallery();
                else showPage(target, true);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Lume.px(64 + 2 * NavItem.MARGIN));
            lp.topMargin = i == 0 ? Lume.px(36) : -Lume.px(8);
            side.addView(n, lp);
            nav[i] = n;
        }

        side.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));
        TextView tag = text("A LUZ DA SUA TV", Lume.inter600, 20, Lume.AMBAR, 0.16f);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.leftMargin = Lume.px(24);
        side.addView(tag, tl);
        TextView motto = text("Assista. Ouça. Contemple.", Lume.inter400, 20, Lume.SEC2, 0f);
        LinearLayout.LayoutParams mo = new LinearLayout.LayoutParams(-2, -2);
        mo.leftMargin = Lume.px(24);
        mo.topMargin = Lume.px(10);
        side.addView(motto, mo);
        return side;
    }

    // ---------------------------------------------------------------- telas

    private void showPage(int p, boolean animate) {
        if (p == page) return;
        page = p;
        for (int i = 0; i < nav.length; i++) nav[i].setActive(i == p || (p == PICK && i == PAISAGENS));
        backdrop.unfollowAll();
        statusViews.clear();
        View v;
        switch (p) {
            case APPS:
                v = buildAppsPage();
                break;
            case INPUTS:
                v = buildInputsPage();
                break;
            case SETTINGS:
                v = buildSettingsPage();
                break;
            case PICK:
                v = buildPickPage();
                break;
            default:
                if (homePage == null) homePage = buildHomePage();
                v = homePage;
                statusViews.add((TextView) homePage.findViewWithTag("status"));
                break;
        }
        updateStatus();
        pageHost.removeAllViews();
        if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
        pageHost.addView(v);
        if (animate) {
            // Entrada Fluent: sobe 16 px e aparece em 250 ms.
            v.setAlpha(0f);
            v.setTranslationY(Lume.f(16));
            v.animate().alpha(1f).translationY(0f).setDuration(Lume.ENTER_MS).setInterpolator(Lume.ENTER)
                    .withEndAction(() -> Lume.invalidateGlass(pageHost)).start();
        } else {
            v.setAlpha(1f);
            v.setTranslationY(0f);
        }
    }

    private void focusHome() {
        if (homeRow != null && homeRow.getChildCount() > 0) homeRow.getChildAt(0).requestFocus();
    }

    private View buildHomePage() {
        LinearLayout home = column();
        home.setPadding(Lume.px(56), Lume.px(52), Lume.px(56), 0);

        // Topo: saudação | filtros | relógio e data.
        FrameLayout header = new FrameLayout(this);
        header.setClipChildren(false);
        greeting = text("", Lume.inter600, 20, Lume.SEC, 0.18f);
        header.addView(greeting, new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));

        GlassRow filters = new GlassRow(this);
        for (int i = 0; i < FILTERS.length; i++) {
            Pill chip = new Pill(this, FILTERS[i], i == filter ? GlassPainter.NEVOA : GlassPainter.CLEAR, 52, 28, 22, 6);
            final int f = i;
            chip.setOnClickListener(v -> setFilter(f));
            filters.addView(chip);
            chips[i] = chip;
        }
        header.addView(filters, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        LinearLayout time = new LinearLayout(this);
        time.setOrientation(LinearLayout.VERTICAL);
        time.setGravity(Gravity.END);
        clock = text("", Lume.sora600, 42, Lume.NEVOA, -0.02f);
        time.addView(clock);
        date = text("", Lume.inter400, 20, Lume.SEC2, 0f);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-2, -2);
        dl.topMargin = Lume.px(6);
        time.addView(date, dl);
        header.addView(time, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        home.addView(header, new LinearLayout.LayoutParams(-1, Lume.px(84)));

        // Destaque das Paisagens: a foto atual (ou a fixada) atrás do painel de vidro.
        hero = new HeroView(this, Lume.px(CONTENT_W), Lume.px(470), Lume.f(800));
        LinearLayout panel = column();
        panel.addView(text("PAISAGENS  ●  COLEÇÃO LUME", Lume.inter600, 20, Lume.AMBAR, 0.16f));
        TextView title = text("Paisagens", Lume.sora700, 84, Lume.NEVOA, -0.02f);
        LinearLayout.LayoutParams til = new LinearLayout.LayoutParams(-2, -2);
        til.topMargin = Lume.px(14);
        panel.addView(title, til);
        TextView desc = text("Fotos de paisagem em tela cheia, trocando sozinhas. Quando você não está "
                + "assistindo, a TV vira uma janela.", Lume.inter400, 24, Lume.SEC, 0f);
        desc.setLineSpacing(0f, 1.3f);
        LinearLayout.LayoutParams del = new LinearLayout.LayoutParams(-1, -2);
        del.topMargin = Lume.px(14);
        panel.addView(desc, del);
        LinearLayout buttons = row();
        Pill open = new Pill(this, "Ver paisagens", GlassPainter.NEVOA, 64, 34, 22, 12);
        open.setOnClickListener(v -> openGallery());
        buttons.addView(open);
        Pill choose = new Pill(this, "Escolher paisagem", GlassPainter.FROST, 64, 34, 22, 12);
        choose.setOnClickListener(v -> showPage(PICK, true));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
        cl.leftMargin = Lume.px(16 - 24);
        buttons.addView(choose, cl);
        // Pela geometria, ↑ dos botões cairia no menu lateral: liga botões ↔ filtros explicitamente.
        heroButtons[0] = open;
        heroButtons[1] = choose;
        open.setId(View.generateViewId());
        for (Pill chip : chips) chip.setNextFocusDownId(open.getId());
        linkHeroToFilter();
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
        bl.topMargin = Lume.px(30 - 12);
        bl.leftMargin = -Lume.px(12);
        panel.addView(buttons, bl);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(Lume.px(800 - 96), -2);
        pl.leftMargin = Lume.px(40 + 48);
        pl.topMargin = Lume.px(40 + 44);
        hero.addView(panel, pl);
        TextView mark = text("LUME / 01", Lume.inter600, 20, Color.argb(200, 236, 240, 240), 0.22f);
        heroMark = mark;
        FrameLayout.LayoutParams mk = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        mk.rightMargin = Lume.px(44);
        mk.bottomMargin = Lume.px(38);
        hero.addView(mark, mk);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(Lume.px(CONTENT_W), Lume.px(470));
        hl.topMargin = Lume.px(28);
        home.addView(hero, hl);
        heroIndex = -1;
        loadHeroPhoto();

        // Apps principais.
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, -2);
        rl.topMargin = Lume.px(40);
        home.addView(text("Apps principais", Lume.inter600, 24, Lume.NEVOA, 0f), rl);
        homeRow = row();
        LinearLayout.LayoutParams hr = new LinearLayout.LayoutParams(-2, -2);
        hr.topMargin = Lume.px(16 - GlassTile.MARGIN);
        hr.leftMargin = -Lume.px(GlassTile.MARGIN);
        home.addView(homeRow, hr);
        buildHomeTiles();
        fillHomeRow();

        TextView status = text("", Lume.inter400, 20, Lume.SEC2, 0.02f);
        status.setTag("status");
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.topMargin = Lume.px(28 - GlassTile.MARGIN);
        home.addView(status, sl);
        updateClock();
        return home;
    }

    private void buildHomeTiles() {
        homeTiles.clear();
        homeTags.clear();
        PackageManager pm = getPackageManager();
        for (String[] f : FAVORITES) {
            View t = appTile(pm, f[0], f[1]);
            if (t == null) t = missingTile(f[1]);
            homeTiles.add(t);
            homeTags.add(Integer.parseInt(f[2]));
        }
        homeTiles.add(paisagensTile());
        homeTags.add(ART);
        List<View> hdmi = hdmiTiles();
        if (hdmi.isEmpty()) {
            GlassTile t = new GlassTile(this, "HDMI 1", null, Icons.INPUT, "Não encontrada", true);
            t.setOnClickListener(v -> toast("Nenhuma entrada HDMI encontrada"));
            homeTiles.add(t);
        } else {
            homeTiles.add(hdmi.get(0));
        }
        homeTags.add(WATCH);
        // "Outros apps" fica em todos os filtros: nenhum app fica inacessível.
        GlassTile more = new GlassTile(this, "Outros apps", null, Icons.MORE, "Todos os apps", false);
        more.setOnClickListener(v -> openAllApps());
        homeTiles.add(more);
        homeTags.add(WATCH | LISTEN | ART);
    }

    private void fillHomeRow() {
        homeRow.removeAllViews();
        int n = 0;
        for (int i = 0; i < homeTiles.size(); i++) {
            if ((homeTags.get(i) & FILTER_MASK[filter]) == 0) continue;
            View t = homeTiles.get(i);
            if (t.getParent() != null) ((ViewGroup) t.getParent()).removeView(t);
            t.setFocusedByDefault(n == 0); // primeiro D-pad sem foco cai no primeiro app, não no menu
            homeRow.addView(t, tileParams(n++ > 0));
        }
    }

    private void setFilter(int f) {
        if (f == filter) return;
        filter = f;
        for (int i = 0; i < chips.length; i++) chips[i].setKind(i == f ? GlassPainter.NEVOA : GlassPainter.CLEAR);
        linkHeroToFilter();
        fillHomeRow();
        homeRow.setAlpha(0f);
        homeRow.animate().alpha(1f).setDuration(Lume.ENTER_MS).setInterpolator(Lume.ENTER).start();
    }

    private void linkHeroToFilter() {
        Pill active = chips[filter];
        if (active.getId() == View.NO_ID) active.setId(View.generateViewId());
        for (Pill b : heroButtons) if (b != null) b.setNextFocusUpId(active.getId());
    }

    private View buildAppsPage() {
        PackageManager pm = getPackageManager();
        Set<String> shown = new HashSet<>();
        shown.add(getPackageName());
        LinearLayout col = pageColumn("Apps");

        List<View> fav = new ArrayList<>();
        for (String[] f : FAVORITES) {
            View t = appTile(pm, f[0], f[1]);
            if (t != null) fav.add(t);
            shown.add(f[0]);
        }
        fav.add(paisagensTile());
        section(col, "Favoritos", fav);
        section(col, "Entradas", hdmiTiles());
        section(col, "Sistema", systemTiles());

        // Demais apps habilitados (TV e celular), em ordem alfabética.
        List<ResolveInfo> list = new ArrayList<>();
        list.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        list.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0));
        list.sort((a, b) -> String.valueOf(a.loadLabel(pm)).compareToIgnoreCase(String.valueOf(b.loadLabel(pm))));
        List<View> others = new ArrayList<>();
        for (ResolveInfo ri : list) {
            String pkg = ri.activityInfo.packageName;
            if (shown.add(pkg)) {
                View t = appTile(pm, pkg, null);
                if (t != null) others.add(t);
            }
        }
        View first = section(col, "Outros apps", others);
        if (first != null) first.setTag("others");
        return scroll(col);
    }

    private View buildInputsPage() {
        LinearLayout col = pageColumn("Entradas");
        List<View> hdmi = hdmiTiles();
        if (hdmi.isEmpty()) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = Lume.px(32);
            col.addView(text("Nenhuma entrada HDMI encontrada.", Lume.inter400, 22, Lume.SEC, 0f), lp);
        } else {
            section(col, "HDMI", hdmi);
        }
        return scroll(col);
    }

    private View buildSettingsPage() {
        LinearLayout col = pageColumn("Configurações");
        section(col, "Sistema", systemTiles());
        section(col, "Paisagens", galleryTiles());
        TextView status = text("", Lume.inter400, 22, Lume.SEC, 0.02f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = Lume.px(40);
        col.addView(status, lp);
        statusViews.add(status);
        TextView note = text("O vidro baixa sozinho para Médio com a TV quente e desliga em temperatura "
                + "severa. Tempo real anima o fundo e só roda com a tela inicial aberta. As Paisagens também "
                + "aparecem em Configurações da TV > Protetor de tela, como \"lume · Paisagens\". Fotos suas "
                + "vão na pasta lume/paisagens do armazenamento da TV.", Lume.inter400, 20, Lume.SEC2, 0f);
        note.setLineSpacing(0f, 1.3f);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(Lume.px(900), -2);
        nl.topMargin = Lume.px(12);
        col.addView(note, nl);
        return scroll(col);
    }

    private List<View> systemTiles() {
        List<View> out = new ArrayList<>();
        GlassTile s = new GlassTile(this, "Configurações", null, Icons.SETTINGS, "Da TV", false);
        s.setOnClickListener(v -> launch(new Intent(Settings.ACTION_SETTINGS), "Configurações"));
        out.add(s);
        String sub = effectiveLevel() < userLevel ? "Limitado pela temperatura" : "OK para mudar";
        GlassTile g = new GlassTile(this, "Vidro: " + LEVEL_NAMES[userLevel], null, Icons.GLASS, sub, false);
        g.setTag("glass");
        g.setOnClickListener(this::cycleLevel);
        out.add(g);
        return out;
    }

    private void openAllApps() {
        showPage(APPS, true);
        View t = pageHost.findViewWithTag("others");
        if (t != null) t.requestFocus();
    }

    // ---------------------------------------------------------------- paisagens

    /** Cartões de ajuste da galeria: intervalo, relógio e brilho reduzido (lembrados nas preferências). */
    private List<View> galleryTiles() {
        List<View> out = new ArrayList<>();
        int iv = prefs.getInt("gal_interval", 1);
        out.add(settingTile("gal_interval", "Troca: " + GalleryView.INTERVAL_NAMES[iv], Icons.ART, "OK para mudar", v -> {
            prefs.edit().putInt("gal_interval", (iv + 1) % GalleryView.INTERVALS.length).apply();
            rebuild("gal_interval");
        }));
        boolean clockOn = prefs.getBoolean("gal_clock", true);
        out.add(settingTile("gal_clock", "Relógio: " + (clockOn ? "Ligado" : "Desligado"), Icons.SETTINGS, "Na galeria", v -> {
            prefs.edit().putBoolean("gal_clock", !clockOn).apply();
            rebuild("gal_clock");
        }));
        int dim = prefs.getInt("gal_dim", 0);
        out.add(settingTile("gal_dim", dim == 0 ? "Brilho: normal" : "Brilho: -" + GalleryView.DIMS[dim] + "%", Icons.GLASS,
                "Brilho reduzido", v -> {
                    prefs.edit().putInt("gal_dim", (dim + 1) % GalleryView.DIMS.length).apply();
                    rebuild("gal_dim");
                }));
        return out;
    }

    private View settingTile(String tag, String label, int glyph, String sub, View.OnClickListener click) {
        GlassTile t = new GlassTile(this, label, null, glyph, sub, false);
        t.setTag(tag);
        t.setOnClickListener(click);
        return t;
    }

    /** Reconstrói a tela atual e devolve o foco ao cartão {@code tag}. */
    private void rebuild(String tag) {
        int p = page;
        page = -1;
        showPage(p, false);
        View again = pageHost.findViewWithTag(tag);
        if (again != null) again.requestFocus();
    }

    /** "Escolher paisagem": "Rotativo" e uma miniatura por foto; OK fixa a foto (ou volta ao rotativo). */
    private View buildPickPage() {
        LinearLayout col = pageColumn("Escolher paisagem");
        List<Photos.Photo> photos = Photos.reload(this);
        int fixed = GalleryView.fixedIndex(this, prefs);
        List<View> tiles = new ArrayList<>();
        String every = GalleryView.INTERVAL_NAMES[prefs.getInt("gal_interval", 1)];
        GlassTile rot = new GlassTile(this, "Rotativo", null, Icons.ART,
                fixed < 0 ? "Ativo, a cada " + every : "Trocar a cada " + every, false);
        rot.setTag("pick-rot");
        rot.setOnClickListener(v -> {
            prefs.edit().remove("gal_fixed").apply();
            rebuild("pick-rot");
        });
        tiles.add(rot);
        for (int i = 0; i < photos.size(); i++) {
            Photos.Photo ph = photos.get(i);
            ThumbTile t = new ThumbTile(this, ph.title, i == fixed);
            String tag = "pick-" + i;
            t.setTag(tag);
            final int index = i;
            t.setOnClickListener(v -> {
                prefs.edit().putString("gal_fixed", ph.key()).putInt("gal_index", index).apply();
                loadHeroPhoto(); // o destaque passa a mostrar a foto fixada
                rebuild(tag);
            });
            tiles.add(t);
            bg.execute(() -> {
                Bitmap b = Photos.thumb(this, ph);
                runOnUiThread(() -> t.setThumb(b));
            });
        }
        section(col, photos.size() == 1 ? "1 foto" : photos.size() + " fotos", tiles);
        // Ao abrir, o foco já fica na foto fixada (ou em Rotativo).
        final String focusTag = fixed >= 0 ? "pick-" + fixed : "pick-rot";
        col.post(() -> {
            View f = col.findViewWithTag(focusTag);
            if (f != null && !col.hasFocus()) f.requestFocus();
        });
        TextView note = text("A foto fixada fica parada na galeria e no protetor de tela. Para voltar a "
                + "trocar sozinho, escolha Rotativo.", Lume.inter400, 20, Lume.SEC2, 0f);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(Lume.px(900), -2);
        nl.topMargin = Lume.px(28);
        col.addView(note, nl);
        return scroll(col);
    }

    /** Põe no destaque a foto fixada ou a última vista na galeria (decodifica em segundo plano). */
    private void loadHeroPhoto() {
        if (hero == null) return;
        int n = Photos.count(this);
        if (n == 0) return;
        int fixed = GalleryView.fixedIndex(this, prefs);
        final int index = Math.floorMod(fixed >= 0 ? fixed : prefs.getInt("gal_index", 0), n);
        if (index == heroIndex) return;
        final boolean animate = heroIndex >= 0;
        heroIndex = index;
        final int w = Lume.px(CONTENT_W), h = Lume.px(470);
        final HeroView target = hero;
        bg.execute(() -> {
            Bitmap full = Photos.decode(this, Photos.get(this, index), w, h);
            if (full == null) return;
            Bitmap crop = Photos.cover(full, w, h, 0.5f);
            full.recycle();
            Bitmap soft = Photos.blur(crop);
            runOnUiThread(() -> {
                target.setPhoto(crop, soft, animate);
                heroMark.setText(String.format(Locale.ROOT, "LUME / %02d", index + 1));
            });
        });
    }

    // ---------------------------------------------------------------- blocos de layout

    private LinearLayout pageColumn(String title) {
        LinearLayout col = column();
        col.setPadding(Lume.px(56), Lume.px(52), Lume.px(56), Lume.px(64));
        col.addView(text(title, Lume.sora600, 48, Lume.NEVOA, -0.02f));
        return col;
    }

    /** Título de seção e grade de 6 colunas. Devolve o primeiro cartão (ou null). */
    private View section(LinearLayout col, String label, List<View> tiles) {
        if (tiles.isEmpty()) return null;
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-2, -2);
        hl.topMargin = Lume.px(36);
        col.addView(text(label, Lume.inter600, 24, Lume.NEVOA, 0f), hl);
        LinearLayout line = null;
        for (int i = 0; i < tiles.size(); i++) {
            if (i % COLS == 0) {
                line = row();
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.topMargin = Lume.px((i == 0 ? 16 : GAP) - (i == 0 ? 1 : 2) * GlassTile.MARGIN);
                lp.leftMargin = -Lume.px(GlassTile.MARGIN);
                col.addView(line, lp);
            }
            line.addView(tiles.get(i), tileParams(i % COLS > 0));
        }
        return tiles.get(0);
    }

    private LinearLayout.LayoutParams tileParams(boolean notFirst) {
        float m = 2 * GlassTile.MARGIN;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Lume.px(CARD_W + m), Lume.px(CARD_H + m));
        if (notFirst) lp.leftMargin = Lume.px(GAP - m);
        return lp;
    }

    private View scroll(LinearLayout col) {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipChildren(false);
        sv.setOnScrollChangeListener((v, x, y, ox, oy) -> Lume.invalidateGlass(v));
        sv.addView(col);
        return sv;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setClipChildren(false);
        l.setClipToPadding(false);
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setClipChildren(false);
        l.setClipToPadding(false);
        return l;
    }

    // ---------------------------------------------------------------- cartões

    private Intent launchIntent(PackageManager pm, String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        return i != null ? i : pm.getLaunchIntentForPackage(pkg);
    }

    private View appTile(PackageManager pm, String pkg, String name) {
        Intent launch = launchIntent(pm, pkg);
        if (launch == null) return null;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if (!ai.enabled) return null;
            Drawable icon = pm.getApplicationIcon(ai);
            String label = name != null ? name : String.valueOf(pm.getApplicationLabel(ai));
            GlassTile t = new GlassTile(this, label, icon, -1, null, false);
            t.setOnClickListener(v -> launch(launch, label));
            return t;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private View missingTile(String name) {
        GlassTile t = new GlassTile(this, name, null, -1, "Não instalado", true);
        t.setOnClickListener(v -> toast(name + " não está instalado"));
        return t;
    }

    private View paisagensTile() {
        int n = Photos.count(this);
        GlassTile t = new GlassTile(this, "Paisagens", null, Icons.ART, n == 1 ? "1 foto" : n + " fotos", false);
        t.setOnClickListener(v -> openGallery());
        return t;
    }

    private List<View> hdmiTiles() {
        List<View> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return out;
        for (TvInputInfo in : tim.getTvInputList()) {
            if (!in.isPassthroughInput() || in.getType() != TvInputInfo.TYPE_HDMI || in.isHidden(this)) continue;
            CharSequence name = in.loadCustomLabel(this);
            if (name == null) name = in.loadLabel(this);
            String label = String.valueOf(name);
            Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(in.getId()));
            GlassTile t = new GlassTile(this, label, null, Icons.INPUT, null, false);
            t.setOnClickListener(v -> launch(i, label));
            out.add(t);
        }
        return out;
    }

    private void openGallery() {
        launch(new Intent(this, PaisagensActivity.class), "Paisagens");
    }

    private void launch(Intent intent, String label) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            toast("Não foi possível abrir: " + label);
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- relógio e status

    private void updateClock() {
        if (clock == null) return;
        Date now = new Date();
        clock.setText(DateFormat.getTimeFormat(this).format(now));
        Locale loc = Locale.getDefault();
        String d = new java.text.SimpleDateFormat(DateFormat.getBestDateTimePattern(loc, "EEEEdMMMM"), loc).format(now);
        date.setText(d.isEmpty() ? d : d.substring(0, 1).toUpperCase(loc) + d.substring(1));
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText(h >= 5 && h < 12 ? "BOM DIA" : h >= 12 && h < 18 ? "BOA TARDE" : "BOA NOITE");
    }

    /** "4K · 120 Hz · Dolby Vision · Vidro: Alto", lido da tela atual. */
    private void updateStatus() {
        if (statusViews.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        Display d = getDisplay();
        if (d != null) {
            Display.Mode m = d.getMode();
            int pw = Math.max(m.getPhysicalWidth(), m.getPhysicalHeight());
            sb.append(pw >= 3840 ? "4K" : pw >= 2560 ? "QHD" : pw >= 1920 ? "Full HD" : "HD");
            sb.append(" · ").append(Math.round(d.getRefreshRate())).append(" Hz");
            Display.HdrCapabilities caps = d.getHdrCapabilities();
            boolean dv = false, hdr10 = false;
            if (caps != null) {
                for (int t : caps.getSupportedHdrTypes()) {
                    if (t == Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) dv = true;
                    if (t == Display.HdrCapabilities.HDR_TYPE_HDR10) hdr10 = true;
                }
            }
            if (dv) sb.append(" · Dolby Vision");
            else if (hdr10) sb.append(" · HDR10");
            sb.append(" · ");
        }
        int eff = effectiveLevel();
        sb.append("Vidro: ").append(LEVEL_NAMES[eff]);
        if (eff < userLevel) sb.append(" (proteção térmica)");
        for (TextView t : statusViews) if (t != null) t.setText(sb);
    }

    private TextView text(String s, Typeface tf, float size, int color, float spacing) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTypeface(tf);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, Lume.f(size));
        t.setTextColor(color);
        t.setLetterSpacing(spacing);
        t.setIncludeFontPadding(false);
        return t;
    }
}
