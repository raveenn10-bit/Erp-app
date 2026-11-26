package lk.com.harshapex.sanjeewaerp;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import java.util.UUID;
import org.json.JSONObject;

/**
 * Foreground service that records the rep's location while on duty — also with
 * the screen locked or the app in the background — and uploads it every 30 s.
 * Shows a permanent "On duty" notification while running (Android requirement,
 * and so the rep always knows tracking is on). Stops when duty ends.
 */
public class GpsTrackingService extends Service {
    static final String ACTION_START = "lk.com.harshapex.sanjeewaerp.GPS_START";
    static final String ACTION_STOP = "lk.com.harshapex.sanjeewaerp.GPS_STOP";
    static final String CHANNEL = "duty_tracking";
    static final int NOTIFICATION_ID = 4711;
    static volatile boolean running = false;

    private HandlerThread thread;
    private Handler worker;
    private FusedLocationProviderClient fused;
    private LocationCallback callback;
    private String sessionId, startedAt;
    private long intervalMs = 30000, maxDutyMs = 14L * 3600 * 1000, startedMs;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (System.currentTimeMillis() - startedMs > maxDutyMs) {
                autoEnd();
                return;
            }
            GpsUploader.flush(GpsTrackingService.this, sessionId, startedAt, true);
            worker.postDelayed(this, intervalMs);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("gps-worker");
        thread.start();
        worker = new Handler(thread.getLooper());
        fused = LocationServices.getFusedLocationProviderClient(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopTracking(true);
            return START_NOT_STICKY;
        }
        SharedPreferences p = GpsUploader.prefs(this);
        sessionId = p.getString("sessionId", null);
        startedAt = p.getString("startedAt", null);
        if (sessionId == null || startedAt == null || !hasLocationPermission()) {
            // Restarted by Android after duty already ended (or permission removed): nothing to do.
            stopSelf();
            return START_NOT_STICKY;
        }
        intervalMs = Math.max(10, p.getInt("intervalSec", 30)) * 1000L;
        maxDutyMs = Math.max(1, p.getInt("maxHours", 14)) * 3600L * 1000;
        startedMs = p.getLong("startedMs", System.currentTimeMillis());
        try {
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION : 0;
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(p), type);
        } catch (Exception e) {
            // Android blocks starting location tracking from the background (e.g. after a
            // restart). The app restarts it the next time it is opened.
            p.edit().putString("lastError", "Tracking paused — open the app to continue.").apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) startUpdates();
        running = true;
        return START_STICKY;
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressWarnings("MissingPermission")
    private void startUpdates() {
        LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setMinUpdateDistanceMeters(5) // standing still → no new fixes (saves battery); heartbeat keeps "online"
            .setMaxUpdateDelayMillis(intervalMs)
            .setWaitForAccurateLocation(false)
            .build();
        callback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                for (Location l : result.getLocations()) record(l);
            }
        };
        try {
            fused.requestLocationUpdates(req, callback, thread.getLooper());
        } catch (SecurityException e) {
            GpsUploader.prefs(this).edit().putString("lastError", "Location permission was removed.").apply();
        }
        worker.postDelayed(tick, 5000);
    }

    private void record(Location l) {
        if (sessionId == null) return;
        GpsQueue.Point x = new GpsQueue.Point();
        x.id = UUID.randomUUID().toString();
        x.sessionId = sessionId;
        x.sessionStarted = startedAt;
        x.recordedAt = GpsUploader.iso(l.getTime() > 0 ? l.getTime() : System.currentTimeMillis());
        x.lat = l.getLatitude();
        x.lng = l.getLongitude();
        x.acc = l.hasAccuracy() ? (double) l.getAccuracy() : null;
        x.speed = l.hasSpeed() ? (double) l.getSpeed() : null;
        x.heading = l.hasBearing() ? (double) l.getBearing() : null;
        x.battery = GpsUploader.battery(this);
        GpsQueue.get(this).addPoint(x);
        try {
            JSONObject fix = new JSONObject();
            fix.put("lat", x.lat);
            fix.put("lng", x.lng);
            fix.put("accuracy", x.acc == null ? JSONObject.NULL : x.acc);
            fix.put("time", x.recordedAt);
            GpsUploader.prefs(this).edit().putString("lastFix", fix.toString()).apply();
        } catch (Exception ignored) { /* status only */ }
    }

    /** Forgotten "End duty": end it on the server and stop (battery + privacy). */
    private void autoEnd() {
        try {
            JSONObject b = new JSONObject();
            b.put("id", sessionId);
            b.put("startedAt", startedAt);
            b.put("source", "android");
            b.put("endedAt", GpsUploader.iso(System.currentTimeMillis()));
            b.put("endReason", "auto");
            GpsQueue.get(this).addRequest("/tracking/duty", b.toString());
        } catch (Exception ignored) { /* the app ends it on next open as well */ }
        GpsUploader.prefs(this).edit().putBoolean("autoEnded", true).apply();
        stopTracking(true);
    }

    private void stopTracking(boolean finalUpload) {
        running = false;
        if (callback != null) fused.removeLocationUpdates(callback);
        callback = null;
        final String s = sessionId, st = startedAt;
        GpsUploader.prefs(this).edit().remove("sessionId").remove("startedAt").remove("startedMs").apply();
        sessionId = null;
        if (worker != null) {
            worker.removeCallbacks(tick);
            if (finalUpload) worker.post(() -> GpsUploader.flush(this, s, st, false));
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        if (worker != null) worker.post(this::stopSelf);
        else stopSelf();
    }

    @Override
    public void onDestroy() {
        running = false;
        if (callback != null) fused.removeLocationUpdates(callback);
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private Notification notification(SharedPreferences p) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Duty tracking", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shown while you are on duty and your location is shared with the office.");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(p.getString("title", "On duty"))
            .setContentText(p.getString("text", "Location is shared with the office."))
            .setStyle(new NotificationCompat.BigTextStyle().bigText(p.getString("text", "Location is shared with the office.")))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pi)
            .build();
    }

    static void start(Context ctx) {
        ContextCompat.startForegroundService(ctx, new Intent(ctx, GpsTrackingService.class).setAction(ACTION_START));
    }

    static void stop(Context ctx) {
        if (!running) {
            GpsUploader.prefs(ctx).edit().remove("sessionId").remove("startedAt").remove("startedMs").apply();
            return;
        }
        ctx.startService(new Intent(ctx, GpsTrackingService.class).setAction(ACTION_STOP));
    }

}
