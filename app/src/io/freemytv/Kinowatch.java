package io.freemytv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * kino.watch (api.srvkp.com) client: device-flow activation, "continue watching" history, item
 * metadata and search. The tokens live in Prefs; while the device is not activated the Continue
 * tile shows the activation code and the poll loop waits for the user's confirmation at
 * kino.watch/device (same flow the kino.watch app itself uses).
 */
final class Kinowatch {
    private Kinowatch() {}

    private static final String API = "https://api.srvkp.com/v1/";
    private static final String OAUTH_DEVICE = "https://api.srvkp.com/oauth2/device";
    private static final String OAUTH_TOKEN = "https://api.srvkp.com/oauth2/token";
    private static final String CLIENT_ID = "xbmc";
    private static final String CLIENT_SECRET = "cgg3gtifu46urtfp2zp1nqtba0k2ezxh";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36";
    private static final long CODE_TTL = 290_000L, HISTORY_EVERY = 3600_000L, RETRY_EVERY = 10 * 60_000L,
            POLL_EVERY = 6_000L;   // hourly cache for all recommendations (user 2026-11-08); failed/empty
            // retries at most every RETRY_EVERY

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** HomeView sets this: re-render the hero and the drawer when the data changes. */
    static volatile Runnable onUpdate;

    private static Context app;
    private static boolean polling, fetching;
    private static long lastPoll, lastCodeAttempt, lastAttempt;

    static void init(Context c) {
        if (app != null) return;
        // One driver: the HOME UI process — the manifest isolates HomeActivity in ":home", and the picks are
        // in-memory statics, so a fetch in any other process fills a list the UI never sees (seen live
        // 2026-11-08: default-process fetch, empty Недавние tab on the TV). Other processes must also not
        // tick: they would race the SINGLE-USE refresh token (first refresh wins, the rest invalidate).
        if (!android.app.Application.getProcessName().endsWith(":home")) return;
        app = c.getApplicationContext();
        // A build may carry a refresh token (config.properties "kw_refresh_token"): the TV starts activated,
        // without the code dance. Without it the Continue tile shows the activation code instead.
        if (Prefs.kw("refresh_token").isEmpty() && !Config.KW_REFRESH_TOKEN.isEmpty())
            Prefs.kwPut("refresh_token", Config.KW_REFRESH_TOKEN);
        loadCache();   // last good lists at once — cards never wait for the API
        MAIN.postDelayed(Kinowatch::tick, 4000);
        IO.execute(() -> guard(Kinowatch::fetchHistory));   // fresh history at every boot: the shared
        // history_at may have been stamped moments ago by a previous process, and waiting a full
        // HISTORY_EVERY after a reinstall (or TV restart) shows stale "continue watching".
    }

    // MARK: - state

    static boolean activated() {
        return !Prefs.kw("refresh_token").isEmpty();
    }

    /** The Continue tile label: the activation code while waiting, then just "Продолжить". */
    static String tileLabel() {
        if (activated()) return "Продолжить";
        String uc = Prefs.kw("user_code");
        if (!uc.isEmpty() && System.currentTimeMillis() - Prefs.kwLong("code_at") < CODE_TTL)
            return "Код: " + uc;
        return "Продолжить";
    }

    /** Activation URL for the user's phone. */
    static String verificationUri() {
        return Prefs.kw("verify_uri").isEmpty() ? "https://kino.watch/device" : Prefs.kw("verify_uri");
    }

    private static synchronized void tick() {
        if (app == null) return;
        MAIN.postDelayed(Kinowatch::tick, 30_000);   // the retry loop: a failed fetch (e.g. one 502) tries again
        long now = System.currentTimeMillis();
        if (activated()) {
            long exp = Prefs.kwLong("access_expire");
            if (exp > 0 && now > exp - 120_000)
                IO.execute(() -> guard(Kinowatch::refreshTokens));
            // The history changes rarely (user 2026-11-08: "не чаще пары часов") — fetch every 2 h, and on
            // failure/empty retry at most every 10 min, not on every 30 s tick (don't hammer the API).
            if ((Recs.kwPicks.isEmpty() || now - Prefs.kwLong("history_at") > HISTORY_EVERY)
                    && now - lastAttempt > RETRY_EVERY) {
                lastAttempt = now;
                IO.execute(() -> guard(Kinowatch::fetchHistory));   // empty in THIS process (boot, or the
                // fetch failed): keep retrying — history_at may be fresh from a previous process' run
            }
            return;
        }
        if (polling || fetching) return;
        String dc = Prefs.kw("device_code");
        boolean fresh = !dc.isEmpty() && now - Prefs.kwLong("code_at") < CODE_TTL;
        if (!fresh) {
            if (now - lastCodeAttempt > 60_000) {
                lastCodeAttempt = now;
                IO.execute(() -> guard(Kinowatch::requestCode));
            }
        } else if (now - lastPoll > POLL_EVERY) {
            lastPoll = now;
            polling = true;
            IO.execute(() -> {
                try {
                    poll(dc);
                } catch (Exception e) {
                    if (!String.valueOf(e.getMessage()).contains("authorization_pending"))
                        AppLog.w("kw poll: " + e.getMessage());
                } finally {
                    polling = false;
                }
            });
        }
    }

    private static void guard(ThrowingCall call) {
        try {
            call.run();
        } catch (Exception e) {
            AppLog.w("kw: " + e.getMessage());
        }
    }

    private interface ThrowingCall { void run() throws Exception; }

    // MARK: - auth

    /** Asks the API for a fresh activation pair (code shown on the Continue tile + link for the phone). */
    private static void requestCode() throws Exception {
        JSONObject o = post(OAUTH_DEVICE, "grant_type=device_code&client_id=" + CLIENT_ID
                + "&client_secret=" + CLIENT_SECRET);
        Prefs.kwPut("device_code", o.getString("code"));
        Prefs.kwPut("user_code", o.getString("user_code"));
        Prefs.kwPut("verify_uri", o.optString("verification_uri", "https://kino.watch/device"));
        Prefs.kwPutLong("code_at", System.currentTimeMillis());
        AppLog.w("kw: activation code " + o.getString("user_code"));
        bump();
    }

    private static void poll(String deviceCode) throws Exception {
        JSONObject o = post(OAUTH_DEVICE, "grant_type=device_token&client_id=" + CLIENT_ID
                + "&client_secret=" + CLIENT_SECRET + "&code=" + deviceCode);
        storeTokens(o);
        Prefs.kwPut("device_code", "");
        Prefs.kwPutLong("history_at", 0);
        AppLog.w("kw: activated");
        bump();
        IO.execute(() -> guard(Kinowatch::fetchHistory));
    }

    private static void refreshTokens() throws Exception {
        String rt = Prefs.kw("refresh_token");
        JSONObject o = post(OAUTH_TOKEN, "grant_type=refresh_token&client_id=" + CLIENT_ID
                + "&client_secret=" + CLIENT_SECRET + "&refresh_token=" + rt);
        storeTokens(o);
        AppLog.w("kw: token refreshed");
    }

    private static void storeTokens(JSONObject o) throws Exception {
        Prefs.kwPut("refresh_token", o.getString("refresh_token"));
        Prefs.kwPut("access_token", o.getString("access_token"));
        Prefs.kwPutLong("access_expire", System.currentTimeMillis()
                + o.optLong("expires_in", 86_400) * 1000L);
    }

    private static void ensureAccess() throws Exception {
        if (Prefs.kw("access_token").isEmpty() || System.currentTimeMillis() > Prefs.kwLong("access_expire"))
            refreshTokens();
    }

    // MARK: - data

    /** History → the home screen: "continue" (unfinished) leads every tab, "recent" fills the Недавние tab.
     *  ATOMIC: the visible cards change only when new items AND their images are on disk — the API's bad
     *  days must never blank or gray the row (user 2026-11-08). */
    private static void fetchHistory() throws Exception {
        if (fetching) return;
        fetching = true;
        lastAttempt = System.currentTimeMillis();
        try {
            ensureAccess();
            JSONObject o = get("history?perpage=20");
            List<Recs.Pick> cont = new ArrayList<>(), recent = new ArrayList<>();
            parseHistory(o, cont, recent);
            if (cont.isEmpty() && recent.isEmpty()) {
                AppLog.w("kw: history came back empty — keeping the cards as they are: " + preview(o));
                return;
            }
            Recs recs = Recs.get(app);
            for (Recs.Pick p : recent) recs.ensureDownloaded(p);   // images first, swap after (cont ⊆ recent)
            recs.setKwPicks(cont);
            recs.setKwRecent(recent);
            saveCache(cont, recent);
            Prefs.kwPutLong("history_at", System.currentTimeMillis());
            AppLog.w("kw: history continue=" + cont.size() + " recent=" + recent.size());
        } finally {
            fetching = false;
        }
    }

    /** The last good lists, saved after every successful swap: a fresh process shows the cards at once,
     *  offline or mid-outage. The stills are already on disk (download() skips existing files). */
    private static void saveCache(List<Recs.Pick> cont, List<Recs.Pick> recent) {
        try {
            Prefs.kwPut("cache", new JSONObject().put("cont", toJson(cont)).put("recent", toJson(recent)).toString());
        } catch (Exception ignored) {
        }
    }

    private static void loadCache() {
        try {
            JSONObject o = new JSONObject(Prefs.kw("cache"));
            List<Recs.Pick> cont = fromJson(o.optJSONArray("cont")), recent = fromJson(o.optJSONArray("recent"));
            if (cont.isEmpty() && recent.isEmpty()) return;
            Recs.get(app).setKwPicks(cont);
            Recs.get(app).setKwRecent(recent);
            AppLog.w("kw: restored continue=" + cont.size() + " recent=" + recent.size());
        } catch (Exception ignored) {
        }
    }

    private static JSONArray toJson(List<Recs.Pick> list) throws Exception {
        JSONArray a = new JSONArray();
        for (Recs.Pick p : list)
            a.put(new JSONObject().put("id", p.kwVideoId).put("kind", p.kind).put("title", p.title)
                    .put("backdrop", p.backdrop).put("meta", p.meta).put("pkg", p.pkg == null ? "" : p.pkg));
        return a;
    }

    private static List<Recs.Pick> fromJson(JSONArray a) {
        List<Recs.Pick> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length() && out.size() < 6; i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Recs.Pick p = new Recs.Pick();
            p.kwVideoId = o.optInt("id");
            p.kind = o.optString("kind", "movie");
            p.title = o.optString("title", "kino.watch");
            p.backdrop = o.optString("backdrop", "");
            p.meta = o.optString("meta", "");
            p.pkg = o.optString("pkg", "").isEmpty() ? "watch.kino" : o.optString("pkg");
            p.detailed = true;
            out.add(p);
        }
        return out;
    }

    /**
     * v1/history is one entry per watched file (a serial appears once per episode), newest first. Titles are
     * deduped by item id; the still of the exact episode watched (media.thumbnail) is the pick's image.
     */
    private static void parseHistory(JSONObject o, List<Recs.Pick> cont, List<Recs.Pick> recent) {
        JSONArray hs = o.optJSONArray("history");
        if (hs == null) return;
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int i = 0; i < hs.length() && (cont.size() < 6 || recent.size() < 6); i++) {
            JSONObject h = hs.optJSONObject(i);
            JSONObject it = h == null ? null : h.optJSONObject("item");
            if (it == null || !seen.add(it.optInt("id"))) continue;
            JSONObject media = h.optJSONObject("media");
            Recs.Pick p = new Recs.Pick();
            p.kwVideoId = it.optInt("id");
            String type = it.optString("type", "movie");
            p.kind = "tv".equals(type) || "serial".equals(type) ? "tv" : "movie";
            p.title = it.optString("title", "kino.watch");
            p.backdrop = media == null ? "" : media.optString("thumbnail", "");
            if (!p.backdrop.startsWith("http")) {
                JSONObject posters = it.optJSONObject("posters");
                p.backdrop = posters == null ? "" : posters.optString("medium", posters.optString("small", ""));
            }
            String ep = media != null && media.optInt("snumber", 0) > 0
                    ? "S" + media.optInt("snumber") + "E" + media.optInt("number", 0) : "";
            long time = h.optLong("time", 0);
            long dur = media == null ? 0 : (long) media.optDouble("duration", 0);
            if (dur <= 0 && it.optJSONObject("duration") != null)
                dur = (long) it.optJSONObject("duration").optDouble("average", 0);
            String prog;
            if (finished(it)) prog = "досмотрено";
            else if (time > 0 && dur > 0) {
                int pct = Math.min(99, Math.round(time * 100f / dur));
                prog = pct + "% · осталось ~" + Math.max(1, Math.round((dur - time) / 60f)) + " мин";
            } else prog = it.optInt("year", 0) > 0 ? String.valueOf(it.optInt("year")) : "kino.watch";
            p.meta = (ep.isEmpty() ? "" : ep + " · ") + prog;
            p.pkg = "watch.kino";
            p.detailed = true;
            if (recent.size() < 6) recent.add(p);
            if (!finished(it) && time > 0 && cont.size() < 6) cont.add(p);
        }
    }

    /** "finished" arrives as true/false and (on some entries) 0/1. */
    private static boolean finished(JSONObject item) {
        return item.optBoolean("finished", false) || item.optInt("finished", 0) != 0;
    }

    /** Metadata for one item (title/year/plot), for the deep-link screen. */
    static JSONObject item(int videoId) throws Exception {
        ensureAccess();
        return get("items/" + videoId);
    }

    /** Search the kino.watch catalog; the RAW object (the caller reads items/results and matches strictly). */
    static JSONObject searchRaw(String query) throws Exception {
        ensureAccess();
        return get("items/search?query=" + java.net.URLEncoder.encode(query, "UTF-8") + "&perpage=10");
    }

    // MARK: - plumbing

    private static final String PREFERRED_APP = "watch.kino";

    private static JSONObject get(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API + path).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(25_000);   // api.srvkp.com has slow spells (seen 3–9 s replies; 8 s timed out on the TV)
        c.setRequestProperty("Authorization", "Bearer " + Prefs.kw("access_token"));
        c.setRequestProperty("user-agent", UA);
        c.setRequestProperty("Accept", "application/json");
        int code = c.getResponseCode();
        String body = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
        if (code != 200) throw new IOException("kw HTTP " + code + ": " + body.substring(0, Math.min(400, body.length())));
        return new JSONObject(body);
    }

    /** Form-post to the OAuth endpoints. 400 responses surface their {"error": ...} body. */
    private static JSONObject post(String url, String form) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setDoOutput(true);
        c.setRequestProperty("user-agent", UA);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream out = c.getOutputStream()) {
            out.write(form.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        String body = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
        if (code == 400) {
            String err = new JSONObject(body).optString("error", body);
            throw new IOException(err);
        }
        if (code != 200) throw new IOException("kw oauth HTTP " + code);
        return new JSONObject(body);
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) b.append(line).append('\n');
        }
        return b.toString().trim();
    }

    private static String preview(JSONObject o) {
        String s = o.toString();
        return s.substring(0, Math.min(400, s.length()));
    }

    /** The drawer tile + activation card read these; version bump redraws the drawer. */
    private static void bump() {
        Prefs.version++;
        Runnable u = onUpdate;
        if (u != null) MAIN.post(u);
    }
}
