package tech.bogomolov.incomingsmsgateway;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persisted progress of a backfill run ({@link BackfillWorker}). Lives in its
 * own SharedPreferences file because the run spans many worker batches and
 * survives process restarts: the UI polls it for the progress bar, and the
 * worker reads the last-processed inbox id to resume where the previous batch
 * stopped.
 */
public class BackfillState {

    private static final String PREFERENCE = "backfill_state";

    public static final String STATUS_IDLE = "idle";
    public static final String STATUS_SCANNING = "scanning";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_CANCELLED = "cancelled";

    // "" means the global (all rules) backfill.
    private static final String KEY_SCOPE = "scope";
    private static final String KEY_STATUS = "status";
    private static final String KEY_TARGET = "target";
    private static final String KEY_DONE = "done";
    private static final String KEY_LAST_ID = "last_id";

    private BackfillState() {
    }

    private static SharedPreferences pref(Context context) {
        return context.getSharedPreferences(PREFERENCE, Context.MODE_PRIVATE);
    }

    /** Starts (or restarts) a backfill for one scope, resetting all progress. */
    public static void begin(Context context, String scope) {
        pref(context).edit()
                .putString(KEY_SCOPE, scope == null ? "" : scope)
                .putString(KEY_STATUS, STATUS_SCANNING)
                .putInt(KEY_TARGET, -1)
                .putInt(KEY_DONE, 0)
                .putLong(KEY_LAST_ID, 0L)
                .commit();
    }

    /** Marks the scan pass finished and records how many messages match. */
    public static void setScanResult(Context context, int target) {
        pref(context).edit()
                .putString(KEY_STATUS, STATUS_RUNNING)
                .putInt(KEY_TARGET, target)
                .putInt(KEY_DONE, 0)
                .commit();
    }

    /** Adds the dispatched count and advances the inbox cursor of one batch. */
    public static void addProgress(Context context, int doneDelta, long lastId) {
        SharedPreferences pref = pref(context);
        pref.edit()
                .putInt(KEY_DONE, pref.getInt(KEY_DONE, 0) + doneDelta)
                .putLong(KEY_LAST_ID, Math.max(pref.getLong(KEY_LAST_ID, 0L), lastId))
                .commit();
    }

    /** Marks the run finished and snaps the progress to 100%. */
    public static void complete(Context context, int done) {
        pref(context).edit()
                .putString(KEY_STATUS, STATUS_DONE)
                .putInt(KEY_TARGET, done)
                .putInt(KEY_DONE, done)
                .commit();
    }

    /** Marks the run cancelled; the worker checks this and stops early. */
    public static void cancel(Context context) {
        pref(context).edit().putString(KEY_STATUS, STATUS_CANCELLED).commit();
    }

    /** Drops the whole stored state. */
    public static void reset(Context context) {
        pref(context).edit().clear().commit();
    }

    public static String getScope(Context context) {
        return pref(context).getString(KEY_SCOPE, "");
    }

    public static String getStatus(Context context) {
        return pref(context).getString(KEY_STATUS, STATUS_IDLE);
    }

    public static int getTarget(Context context) {
        return pref(context).getInt(KEY_TARGET, -1);
    }

    public static int getDone(Context context) {
        return pref(context).getInt(KEY_DONE, 0);
    }

    public static long getLastId(Context context) {
        return pref(context).getLong(KEY_LAST_ID, 0L);
    }

    /** True while a scan or a batch run is in flight. */
    public static boolean isActive(Context context) {
        String status = getStatus(context);
        return STATUS_SCANNING.equals(status) || STATUS_RUNNING.equals(status);
    }
}
