package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen picks ("This morning / afternoon / tonight"): what's trending this week plus recent releases, only from
 * the streaming apps installed on the TV (Netflix, Prime Video, Disney+, Max, Apple TV, Hulu, Paramount+, Peacock,
 * Globoplay...), via TMDB (an open movie database that knows what streams where in each country). Three per hour.
 * "Watch" opens the service's app.
 *
 * Each person uses their own free TMDB key (config.properties "tmdb_key", built into the app). It never goes to the log.
 */
final class Recs {
    static final class Pick {
        int id;
        int kwVideoId;          // kino.watch video id: the pick deep-links to this exact title.
                                // -1 = searched and ABSENT from the kino.watch catalog (hidden from tabs)
        long kwCheckedAt;       // when the catalog lookup happened (ABSENT entries re-check after 7 days)
        String kind;            // "movie" or "tv"
        String title, backdrop, meta = "", app, pkg;
        boolean detailed;
    }

    /**
     * The one thing the home screen renders: full tab triples where every pick already has its still on
     * disk AND decoded. Published atomically — the UI never sees a half-filled state, so a gray card is
     * impossible by construction; a slow network only makes the next swap later, never uglier.
     */
    static final class Snapshot {
        final List<Pick> explore = new ArrayList<>(), movies = new ArrayList<>(),
                shows = new ArrayList<>(), recent = new ArrayList<>();
    }

    private volatile Snapshot published;
    private int publishRetries;   // io-thread only: bounded retry chain while the stills download

    interface Listener { void onRecs(); }

    /** "Continue watching" picks from kino.watch (Kinowatch), always first on the home screen. */
    static final List<Pick> kwPicks = new ArrayList<>();

    /** Recently watched on kino.watch — the "Недавние" tab. */
    static final List<Pick> kwRecent = new ArrayList<>();

    /** The fourth tab's kind: kino.watch history instead of TMDB trending. */
    static final String KW_RECENT = "kw:recent";

    /** Kinowatch swaps in fresh continue-watching picks; the snapshot republishes when the stills are ready. */
    void setKwPicks(List<Pick> list) {
        synchronized (kwPicks) {
            kwPicks.clear();
            kwPicks.addAll(list);
        }
        publishSnapshotAsync();
    }

    void setKwRecent(List<Pick> list) {
        synchronized (kwRecent) {
            kwRecent.clear();
            kwRecent.addAll(list);
        }
        publishSnapshotAsync();
    }

    private static final String IMAGES = "https://image.tmdb.org/t/p/original";   // legacy: only for relative paths

    private static final int MANHA = 0, TARDE = 1, NOITE = 2;
    static final String[] PERIOD_LABEL = {"PARA ESTA MANHÃ", "PARA ESTA TARDE", "PARA ESTA NOITE"};
    static final String[] PERIOD_LABEL_RU = {"ЭТИМ УТРОМ", "СЕГОДНЯ ДНЁМ", "СЕГОДНЯ ВЕЧЕРОМ"};
    static final String[] PERIOD_LABEL_EN = {"THIS MORNING", "THIS AFTERNOON", "TONIGHT"};
    static final String[] PERIOD_LABEL_ES = {"PARA ESTA MAÑANA", "PARA ESTA TARDE", "PARA ESTA NOCHE"};
    static final String[] PERIOD_LABEL_DE = {"HEUTE MORGEN", "HEUTE NACHMITTAG", "HEUTE ABEND"};

    static String periodLabel() {
        return (Ui.ru ? PERIOD_LABEL_RU : Ui.es ? PERIOD_LABEL_ES : Ui.de ? PERIOD_LABEL_DE : Ui.en ? PERIOD_LABEL_EN : PERIOD_LABEL)
                [period(System.currentTimeMillis())];
    }

    private static int period(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour >= 5 && hour < 12 ? MANHA : hour >= 12 && hour < 18 ? TARDE : NOITE;
    }

    private static Recs instance;

    static synchronized Recs get(Context c) {
        if (instance == null) instance = new Recs(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Backdrop> images = new LinkedHashMap<>();   // published picks' bitmaps, ready to draw
    private final java.util.Set<String> pending = new java.util.HashSet<>();
    private volatile boolean light;     // light design: builds only the cards (all three tabs' cards fit in memory)

    /** kino.watch catalog lists (5 per tab) — the whole home screen since the TMDB retirement (2026-11-13). */
    private static final List<Pick> listExplore = new ArrayList<>(), listMovies = new ArrayList<>(),
            listShows = new ArrayList<>();

    /** Kinowatch swaps in fresh catalog lists; the snapshot republishes when the stills are ready. */
    void setLists(List<Pick> explore, List<Pick> movies, List<Pick> shows) {
        synchronized (listExplore) { listExplore.clear(); listExplore.addAll(explore); }
        synchronized (listMovies) { listMovies.clear(); listMovies.addAll(movies); }
        synchronized (listShows) { listShows.clear(); listShows.addAll(shows); }
        publishSnapshotAsync();
    }

    private static List<Pick> allPicks() {
        List<Pick> out = new ArrayList<>();
        synchronized (listExplore) { out.addAll(listExplore); }
        synchronized (listMovies) { out.addAll(listMovies); }
        synchronized (listShows) { out.addAll(listShows); }
        synchronized (kwPicks) { out.addAll(kwPicks); }
        synchronized (kwRecent) { out.addAll(kwRecent); }
        return out;
    }

    /** True until the first catalog lists arrive (Kinowatch's refresh gate). */
    static boolean listsEmpty() {
        return listExplore.isEmpty();
    }

    private Recs(Context c) {
        context = c;
        publishSnapshotAsync();   // whatever was restored (kw cache) goes out at once
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    // MARK: - The published snapshot

    /** The tab's cards as one immutable list; empty until the first snapshot is published. */
    List<Pick> snapshotTab(String kind) {
        Snapshot s = published;
        if (s == null) return new ArrayList<>();
        if (kind == null || "explore".equals(kind)) return s.explore;
        if ("movies".equals(kind)) return s.movies;
        if ("shows".equals(kind)) return s.shows;
        return s.recent;   // KW_RECENT
    }

    /** Serialized on the same single-thread executor as all fetches/downloads — no races. */
    void publishSnapshotAsync() {
        io.execute(() -> {
            try {
                publishSnapshot();
            } catch (Exception e) {
                AppLog.w("snapshot: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        });
    }

    private void publishSnapshot() {
        Snapshot s = new Snapshot();
        synchronized (listExplore) { for (Pick p : listExplore) if (s.explore.size() < 5) s.explore.add(p); }
        synchronized (listMovies) { for (Pick p : listMovies) if (s.movies.size() < 5) s.movies.add(p); }
        synchronized (listShows) { for (Pick p : listShows) if (s.shows.size() < 5) s.shows.add(p); }
        synchronized (kwRecent) { for (Pick p : kwRecent) if (s.recent.size() < 3) s.recent.add(p); }
        boolean ready = true;
        for (List<Pick> tab : java.util.Arrays.asList(s.explore, s.movies, s.shows, s.recent)) {
            for (Pick p : tab) if (!loadImageNow(p)) ready = false;   // download + decode; a miss delays this swap
        }
        if (!ready) {
            // HOLD the old snapshot: publishing a half-warmed one IS the gray flicker (seen live). Retry
            // in the background — each pass re-attempts only the missing stills — and stop after ~1 min;
            // the next lists/history refresh starts a new chain anyway.
            if (publishRetries++ < 20) {
                AppLog.w("snapshot: waiting for stills (try " + publishRetries + ") — old cards stay visible");
                main.postDelayed(this::publishSnapshotAsync, 3000);
                return;
            }
            AppLog.w("snapshot: stills never arrived — keeping the last good snapshot");
            publishRetries = 0;
            return;
        }
        publishRetries = 0;
        published = s;
        AppLog.w("snapshot: published explore=" + s.explore.size() + " movies=" + s.movies.size()
                + " shows=" + s.shows.size() + " recent=" + s.recent.size());
        notifyListeners();
    }

    /** Synchronous download + decode (the pre-warm path); false if the bitmap is still missing. */
    private boolean loadImageNow(Pick p) {
        if (p.backdrop == null) return false;
        try {
            loadImage(p);
        } catch (Exception e) {
            AppLog.w("backdrop fail \u201c" + p.title + "\u201d: "
                    + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
        synchronized (images) { return images.containsKey(p.backdrop); }
    }

    /** The pick's ready backdrop (or null: builds it in the background and notifies when it's ready). */
    Backdrop backdrop(Pick p) {
        if (p == null || p.backdrop == null) return null;
        final String key = p.backdrop;    // the details may swap the image midway
        synchronized (images) {
            Backdrop b = images.get(key);
            if (b != null) return b;
            if (!pending.add(key)) return null;
        }
        io.execute(() -> {
            try {
                loadImage(p);
            } catch (Exception e) {
                AppLog.w("backdrop fail \u201c" + p.title + "\u201d: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            } finally {
                synchronized (images) { pending.remove(key); }
            }
        });
        return null;
    }


    static boolean hasKey(Context c) {
        return !Config.TMDB_KEY.trim().isEmpty();
    }

    /** Last fetch, for the settings screen: {count, error, at}. Written while TMDB still fed the pool. */
    static JSONObject status(Context c) {
        File f = new File(c.getFilesDir(), "recs-status.json");
        if (!f.exists()) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(read(in));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onRecs(); });
    }

    /** Legacy tick entry point (home screen + screensaver): the catalog lists refresh on
     *  Kinowatch's own hourly schedule — nothing to do here. */
    void refreshIfStale() {
    }

    /** Home screen design: the ready images are rebuilt for it (the light design uses cards, not the whole photo). */
    void setLight(boolean value) {
        if (value == light) return;
        light = value;
        synchronized (images) {
            images.clear();
        }
    }

    /** Screensaver: whatever from the catalog lists and history already has its still on disk. */
    List<Pick> showcase() {
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick p : allPicks()) if (hasImage(p) && seen.add(p.title)) out.add(p);
        return out;
    }

    /** The pick's original image if it's already on disk (the screensaver loads it in its own way), or null. */
    File readyImage(Pick p) {
        return hasImage(p) ? imageFile(p) : null;
    }

    /** While the screensaver is showing: make sure every pick's still is on disk and decoded. */
    void prefetchAll() {
        io.execute(() -> {
            for (Pick p : allPicks()) loadImageNow(p);
            notifyListeners();
        });
    }

    static String serviceName(Pick p) {
        return "";   // everything opens in kino.watch itself now
    }

    /** A pick's identity (the home screen diffs its card lists with it). */
    static String key(Pick p) {
        return p.kwVideoId > 0 ? "kw:" + p.kwVideoId : p.kind + ":" + p.id;
    }

    private boolean hasImage(Pick p) {
        return p.backdrop != null && imageFile(p).exists();
    }

    /** Kinowatch: fetch the pick's image to disk if missing (files survive restarts) — the caller swaps the
     *  cards only after this, so new items arrive WITH their pictures, never as gray placeholders. */
    void ensureDownloaded(Pick p) {
        try {
            if (p.backdrop != null && p.backdrop.startsWith("http")) download(p);
        } catch (Exception e) {
            AppLog.w("kw: still fail \u201c" + p.title + "\u201d: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    private File imageFile(Pick p) {
        return new File(new File(context.getCacheDir(), "recs"), p.backdrop.replace("/", ""));
    }

    private void download(Pick p) throws IOException {
        File f = imageFile(p);
        if (f.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        String url = p.backdrop.startsWith("http") ? p.backdrop : IMAGES + p.backdrop;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(25000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        }
        //noinspection ResultOfMethodCallIgnored
        part.renameTo(f);
    }

    private void loadImage(Pick p) {
        synchronized (images) {
            if (images.containsKey(p.backdrop)) return;
        }
        File f = imageFile(p);
        try {
            download(p);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W) opts.inSampleSize *= 2;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
            if (b == null) throw new IOException("unreadable image");
            boolean forLight = light;
            Backdrop backdrop = Backdrop.make(b, forLight);
            if (forLight != light) return;   // the design changed midway: this one is stale
            synchronized (images) {
                images.put(p.backdrop, backdrop);
                while (images.size() > (forLight ? 20 : 4)) images.remove(images.keySet().iterator().next());
            }
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            throw new RuntimeException(e);
        }
    }

    /** Memory pressure: drop the decoded bitmaps (the files stay). */
    void trim() {
        synchronized (images) {
            images.clear();
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
