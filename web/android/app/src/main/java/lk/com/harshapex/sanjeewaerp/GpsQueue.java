package lk.com.harshapex.sanjeewaerp;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

/**
 * On-device queue for GPS points and duty requests recorded by the tracking
 * service. Rows are deleted only after the server confirmed them, so nothing is
 * lost while the phone is offline. Every point has its own UUID → the server
 * ignores copies that are sent twice.
 */
final class GpsQueue extends SQLiteOpenHelper {
    static final int MAX_POINTS = 20000;
    private static GpsQueue instance;

    static synchronized GpsQueue get(Context ctx) {
        if (instance == null) instance = new GpsQueue(ctx.getApplicationContext());
        return instance;
    }

    private GpsQueue(Context ctx) { super(ctx, "gps_queue.db", null, 1); }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE points(id TEXT PRIMARY KEY, session_id TEXT NOT NULL, session_started TEXT NOT NULL, recorded_at TEXT NOT NULL, lat REAL NOT NULL, lng REAL NOT NULL, acc REAL, speed REAL, heading REAL, battery INTEGER)");
        db.execSQL("CREATE INDEX points_session ON points(session_id, recorded_at)");
        db.execSQL("CREATE TABLE requests(id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT NOT NULL, body TEXT NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { /* v1 only */ }

    static final class Point {
        String id, sessionId, sessionStarted, recordedAt;
        double lat, lng;
        Double acc, speed, heading;
        Integer battery;
    }

    synchronized void addPoint(Point p) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("id", p.id);
        v.put("session_id", p.sessionId);
        v.put("session_started", p.sessionStarted);
        v.put("recorded_at", p.recordedAt);
        v.put("lat", p.lat);
        v.put("lng", p.lng);
        v.put("acc", p.acc);
        v.put("speed", p.speed);
        v.put("heading", p.heading);
        v.put("battery", p.battery);
        db.insertWithOnConflict("points", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        // Never fill the phone: keep the newest MAX_POINTS.
        db.execSQL("DELETE FROM points WHERE id IN (SELECT id FROM points ORDER BY recorded_at DESC LIMIT -1 OFFSET " + MAX_POINTS + ")");
    }

    /** Oldest session that still has points waiting (current duty first if it has any). */
    synchronized String[] nextSession(String preferred) {
        SQLiteDatabase db = getReadableDatabase();
        if (preferred != null) {
            try (Cursor c = db.rawQuery("SELECT session_id, session_started FROM points WHERE session_id=? LIMIT 1", new String[] { preferred })) {
                if (c.moveToFirst()) return new String[] { c.getString(0), c.getString(1) };
            }
        }
        try (Cursor c = db.rawQuery("SELECT session_id, session_started FROM points ORDER BY recorded_at LIMIT 1", null)) {
            if (c.moveToFirst()) return new String[] { c.getString(0), c.getString(1) };
        }
        return null;
    }

    synchronized List<Point> points(String sessionId, int limit) {
        List<Point> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, session_id, session_started, recorded_at, lat, lng, acc, speed, heading, battery FROM points WHERE session_id=? ORDER BY recorded_at LIMIT " + limit,
                new String[] { sessionId })) {
            while (c.moveToNext()) {
                Point p = new Point();
                p.id = c.getString(0);
                p.sessionId = c.getString(1);
                p.sessionStarted = c.getString(2);
                p.recordedAt = c.getString(3);
                p.lat = c.getDouble(4);
                p.lng = c.getDouble(5);
                p.acc = c.isNull(6) ? null : c.getDouble(6);
                p.speed = c.isNull(7) ? null : c.getDouble(7);
                p.heading = c.isNull(8) ? null : c.getDouble(8);
                p.battery = c.isNull(9) ? null : c.getInt(9);
                out.add(p);
            }
        }
        return out;
    }

    synchronized void deletePoints(List<Point> pts) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Point p : pts) db.delete("points", "id=?", new String[] { p.id });
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    synchronized int pointCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM points", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    synchronized void addRequest(String path, String body) {
        ContentValues v = new ContentValues();
        v.put("path", path);
        v.put("body", body);
        getWritableDatabase().insert("requests", null, v);
    }

    /** {id, path, body} of the oldest queued request, or null. */
    synchronized String[] nextRequest() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id, path, body FROM requests ORDER BY id LIMIT 1", null)) {
            if (c.moveToFirst()) return new String[] { String.valueOf(c.getLong(0)), c.getString(1), c.getString(2) };
        }
        return null;
    }

    synchronized void deleteRequest(String id) {
        getWritableDatabase().delete("requests", "id=?", new String[] { id });
    }

    synchronized int requestCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM requests", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }
}
