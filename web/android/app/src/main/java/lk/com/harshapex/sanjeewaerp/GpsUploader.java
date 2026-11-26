package lk.com.harshapex.sanjeewaerp;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.webkit.CookieManager;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Sends queued GPS points / duty requests to the ERP Worker with the app's own
 * signed-in session (the same HttpOnly cookie the app uses). Runs on the
 * tracking service's worker thread, or on a background thread from the plugin.
 */
final class GpsUploader {
    static final String PREFS = "gps_tracker";
    private static final Object LOCK = new Object();

    private GpsUploader() {}

    static SharedPreferences prefs(Context ctx) { return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    static Integer battery(Context ctx) {
        try {
            BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            int v = bm == null ? -1 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            return v >= 0 && v <= 100 ? v : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** HTTP status, or -1 if the server could not be reached. */
    static int post(String apiBase, String path, String json) {
        HttpURLConnection con = null;
        try {
            URL url = new URL(apiBase + "/api" + path);
            con = (HttpURLConnection) url.openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(20000);
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json");
            // The Worker's CSRF guard needs the app's Origin (same as lib/http.ts nativeHeaders()).
            con.setRequestProperty("Origin", url.getProtocol() + "://" + url.getAuthority());
            con.setRequestProperty("X-ERP-Client", "android");
            String cookie = CookieManager.getInstance().getCookie(apiBase);
            if (cookie != null && !cookie.isEmpty()) con.setRequestProperty("Cookie", cookie);
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            con.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = con.getOutputStream()) { os.write(body); }
            int code = con.getResponseCode();
            InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream();
            if (in != null) {
                try (InputStream s = in) {
                    ByteArrayOutputStream sink = new ByteArrayOutputStream();
                    byte[] buf = new byte[2048];
                    int n;
                    while ((n = s.read(buf)) > 0 && sink.size() < 65536) sink.write(buf, 0, n);
                }
            }
            return code;
        } catch (Exception e) {
            return -1;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    /**
     * Upload everything waiting: duty requests first, then points in batches of
     * 200 per session. Sends a heartbeat for the running session even when there
     * are no new points (keeps the rep "online" on the admin map).
     */
    static void flush(Context ctx, String activeSession, String activeStarted, boolean heartbeat) {
        synchronized (LOCK) {
            SharedPreferences p = prefs(ctx);
            String apiBase = p.getString("apiBase", null);
            if (apiBase == null || apiBase.isEmpty()) return;
            GpsQueue q = GpsQueue.get(ctx);
            try {
                String[] r;
                while ((r = q.nextRequest()) != null) {
                    int code = post(apiBase, r[1], r[2]);
                    if (!handle(p, code)) return;
                    q.deleteRequest(r[0]); // 2xx, or a 4xx that can never succeed
                }
                boolean sentActive = false;
                for (int guard = 0; guard < 50; guard++) {
                    String[] s = q.nextSession(activeSession);
                    if (s == null) break;
                    List<GpsQueue.Point> pts = q.points(s[0], 200);
                    if (pts.isEmpty()) break;
                    int code = post(apiBase, "/tracking/locations", body(s[0], s[1], pts, battery(ctx)).toString());
                    if (!handle(p, code)) return;
                    q.deletePoints(pts);
                    if (s[0].equals(activeSession)) sentActive = true;
                }
                if (heartbeat && activeSession != null && !sentActive) {
                    int code = post(apiBase, "/tracking/locations", body(activeSession, activeStarted, null, battery(ctx)).toString());
                    handle(p, code);
                }
            } catch (Exception e) {
                p.edit().putString("lastError", "Upload error: " + e.getMessage()).apply();
            }
        }
    }

    /** true = continue with the next item. */
    private static boolean handle(SharedPreferences p, int code) {
        if (code >= 200 && code < 300) {
            p.edit().putLong("lastUploadAt", System.currentTimeMillis()).remove("lastError").apply();
            return true;
        }
        if (code == -1) { p.edit().remove("lastError").apply(); return false; } // offline: try later
        if (code == 401) { p.edit().putString("lastError", "Signed out — open the app and sign in again. Location is kept on the phone.").apply(); return false; }
        if (code >= 500 || code == 429) { p.edit().putString("lastError", "Server busy — will retry.").apply(); return false; }
        p.edit().putString("lastError", "Server rejected some location data (" + code + ").").apply();
        return true; // other 4xx: drop it so the queue keeps moving
    }

    private static JSONObject body(String sessionId, String started, List<GpsQueue.Point> pts, Integer battery) throws Exception {
        JSONObject session = new JSONObject();
        session.put("id", sessionId);
        session.put("startedAt", started);
        session.put("source", "android");
        JSONArray arr = new JSONArray();
        if (pts != null) {
            for (GpsQueue.Point x : pts) {
                JSONObject o = new JSONObject();
                o.put("id", x.id);
                o.put("recordedAt", x.recordedAt);
                o.put("lat", x.lat);
                o.put("lng", x.lng);
                if (x.acc != null) o.put("accuracy", x.acc);
                if (x.speed != null) o.put("speed", x.speed);
                if (x.heading != null) o.put("heading", x.heading);
                if (x.battery != null) o.put("battery", x.battery);
                arr.put(o);
            }
        }
        JSONObject b = new JSONObject();
        b.put("session", session);
        b.put("points", arr);
        JSONObject hb = new JSONObject();
        if (battery != null) hb.put("battery", battery);
        b.put("heartbeat", hb);
        return b;
    }
}
