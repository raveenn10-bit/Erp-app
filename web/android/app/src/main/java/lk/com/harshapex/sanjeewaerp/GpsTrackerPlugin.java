package lk.com.harshapex.sanjeewaerp;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import androidx.core.location.LocationManagerCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.google.android.gms.location.CurrentLocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;
import org.json.JSONObject;

/**
 * Duty GPS tracking for sales reps. JavaScript (web/lib/tracking.ts) starts and
 * stops the foreground service and reads its status; the service records and
 * uploads on its own so tracking continues with the screen locked.
 * Only "while using the app" location is requested — no background-location
 * permission is needed because tracking runs in a visible foreground service.
 */
@CapacitorPlugin(
    name = "GpsTracker",
    permissions = {
        @Permission(alias = "location", strings = { Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION }),
        @Permission(alias = "notifications", strings = { "android.permission.POST_NOTIFICATIONS" })
    }
)
public class GpsTrackerPlugin extends Plugin {

    private boolean locationGranted() { return getPermissionState("location") == PermissionState.GRANTED; }

    private boolean locationEnabled() {
        LocationManager lm = (LocationManager) getContext().getSystemService(android.content.Context.LOCATION_SERVICE);
        return lm != null && LocationManagerCompat.isLocationEnabled(lm);
    }

    @Override
    @PluginMethod
    public void checkPermissions(PluginCall call) {
        if (Build.VERSION.SDK_INT < 33) {
            // Notifications need no runtime permission before Android 13.
            JSObject r = new JSObject();
            r.put("location", getPermissionState("location").toString());
            r.put("notifications", "granted");
            call.resolve(r);
            return;
        }
        super.checkPermissions(call);
    }

    @PluginMethod
    public void start(PluginCall call) {
        String sessionId = call.getString("sessionId"), startedAt = call.getString("startedAt"), apiBase = call.getString("apiBase");
        if (sessionId == null || startedAt == null || apiBase == null || apiBase.isEmpty()) { call.reject("sessionId, startedAt and apiBase are required"); return; }
        if (!locationGranted()) { call.reject("Location permission is needed for duty tracking.", "PERMISSION_DENIED"); return; }
        if (!locationEnabled()) { call.reject("Location (GPS) is turned off. Turn on Location in the phone settings. · GPS සක්‍රිය කරන්න.", "LOCATION_OFF"); return; }
        long startedMs;
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            startedMs = f.parse(startedAt).getTime();
        } catch (Exception e) {
            startedMs = System.currentTimeMillis();
        }
        GpsUploader.prefs(getContext()).edit()
            .putString("sessionId", sessionId)
            .putString("startedAt", startedAt)
            .putLong("startedMs", startedMs)
            .putString("apiBase", apiBase.replaceAll("/+$", ""))
            .putInt("intervalSec", call.getInt("intervalSec", 30))
            .putInt("maxHours", call.getInt("maxHours", 14))
            .putString("title", call.getString("title", "On duty"))
            .putString("text", call.getString("text", "Location is shared with the office every 30 s."))
            .remove("autoEnded")
            .remove("lastError")
            .apply();
        try {
            GpsTrackingService.start(getContext());
            call.resolve();
        } catch (Exception e) {
            call.reject("Could not start tracking: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        GpsTrackingService.stop(getContext());
        call.resolve();
    }

    @PluginMethod
    public void status(PluginCall call) {
        SharedPreferences p = GpsUploader.prefs(getContext());
        JSObject r = new JSObject();
        r.put("running", GpsTrackingService.running);
        r.put("sessionId", p.getString("sessionId", null));
        String fix = p.getString("lastFix", null);
        if (fix != null) {
            try { r.put("lastFix", JSObject.fromJSONObject(new JSONObject(fix))); } catch (Exception ignored) { /* skip */ }
        }
        GpsQueue q = GpsQueue.get(getContext());
        r.put("pending", q.pointCount() + q.requestCount());
        long up = p.getLong("lastUploadAt", 0);
        if (up > 0) r.put("lastUploadAt", GpsUploader.iso(up));
        String err = p.getString("lastError", null);
        if (err == null && !locationEnabled()) err = "Location (GPS) is turned off. Turn it on to keep tracking. · GPS සක්‍රිය කරන්න.";
        if (err != null) r.put("lastError", err);
        r.put("autoEnded", p.getBoolean("autoEnded", false));
        call.resolve(r);
    }

    /** Send points left on the phone from an earlier duty (e.g. recorded offline). */
    @PluginMethod
    public void flush(PluginCall call) {
        new Thread(() -> GpsUploader.flush(getContext(), GpsUploader.prefs(getContext()).getString("sessionId", null), GpsUploader.prefs(getContext()).getString("startedAt", null), false)).start();
        call.resolve();
    }

    @PluginMethod
    @SuppressWarnings("MissingPermission")
    public void getCurrentPosition(PluginCall call) {
        if (!locationGranted()) { call.reject("Location permission is needed.", "PERMISSION_DENIED"); return; }
        if (!locationEnabled()) { call.reject("Location (GPS) is turned off. Turn on Location in the phone settings. · GPS සක්‍රිය කරන්න.", "LOCATION_OFF"); return; }
        int timeout = call.getInt("timeoutMs", 20000);
        CurrentLocationRequest req = new CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(timeout)
            .setMaxUpdateAgeMillis(10000)
            .build();
        CancellationTokenSource cts = new CancellationTokenSource();
        LocationServices.getFusedLocationProviderClient(getContext())
            .getCurrentLocation(req, cts.getToken())
            .addOnSuccessListener((Location l) -> {
                if (l == null) { call.reject("Could not get a GPS fix. Go outside / near a window and try again. · GPS ලැබුණේ නැත.", "TIMEOUT"); return; }
                JSObject r = new JSObject();
                r.put("lat", l.getLatitude());
                r.put("lng", l.getLongitude());
                if (l.hasAccuracy()) r.put("accuracy", (double) l.getAccuracy()); else r.put("accuracy", JSONObject.NULL);
                r.put("time", GpsUploader.iso(l.getTime() > 0 ? l.getTime() : System.currentTimeMillis()));
                call.resolve(r);
            })
            .addOnFailureListener(e -> call.reject("Location error: " + e.getMessage()));
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getContext().getPackageName(), null));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }
}
