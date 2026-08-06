package tech.bogomolov.incomingsmsgateway;

import android.content.Context;
import android.database.Cursor;
import android.provider.Telephony;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.ArrayList;
import java.util.List;

/**
 * Backfills webhook forwarding: reads messages already in the SMS inbox (not
 * just ones arriving after install) and dispatches each through the same
 * per-rule matching and enqueue path as a live SMS. Triggered manually from the
 * main screen; requires {@code READ_SMS}. SIM slot is read from the provider
 * where the column exists and is best-effort otherwise — a rule pinned to a
 * specific SIM only matches when the slot can be determined.
 *
 * <p>The work is split into small, resumable pieces so a large inbox never runs
 * past WorkManager's execution window or gets killed mid-pass. The first run
 * scans the inbox to count how many messages match the scope rules' FROM line
 * (the progress target — counted on the sender alone, so a big inbox produces a
 * number fast). Each following run processes a bounded batch ordered by inbox
 * id, applying the full per-rule filter (sender regex, body filter, SIM slot) as
 * the "removal" pass; unless it was the last page it re-enqueues itself onto the
 * same unique-work chain ({@link ExistingWorkPolicy #APPEND}). Batches therefore
 * keep running one after another even when the process is killed between them,
 * the whole chain can be cancelled at once, and each batch flushes the activity
 * log immediately instead of only at the end. Because the scan counts on FROM
 * only, the eventual done count can land below the target (body/SIM-filtered
 * messages) — an accepted approximation. Progress is persisted in
 * {@link BackfillState} and reported via {@link #setProgress}.
 */
public class BackfillWorker extends Worker {

    // Messages processed per batch run, ordered by inbox id. Small enough that a
    // batch (match + dispatch + log) finishes far below WorkManager's execution
    // limit, large enough that the per-message overhead stays low.
    private static final int BATCH_SIZE = 200;

    // How often the scan pass reports its running match count to the progress
    // bar. Each publish is a SharedPreferences-write-adjacent DB write, so
    // throttling it keeps a huge inbox from stalling on progress updates.
    private static final int PROGRESS_REPORT_EVERY = 200;

    public static final String PROGRESS_DONE = "done";
    public static final String PROGRESS_TARGET = "target";

    // Tag shared by every backfill work request so the UI can observe the whole
    // chain (any scope) with a single LiveData query.
    public static final String TAG = "backfill";

    // Columns probed for the SIM slot, in preference order. Not present on every
    // device; getColumnIndex returns -1 for a missing column without throwing.
    private static final String[] SIM_COLUMNS = {"sim_slot", "sub_id", "subscription"};

    public BackfillWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /**
     * Unique work name for a scope. Cancelling it stops the whole backfill
     * chain; an empty/null key is the global (all rules) backfill.
     */
    static String uniqueName(String configKey) {
        return configKey == null || configKey.isEmpty()
                ? "backfill_global"
                : "backfill_" + configKey;
    }

    private static boolean sameScope(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }

    /** Enqueues a global backfill (every matching rule) as a background job. */
    public static void enqueue(Context context) {
        enqueue(context, null);
    }

    /**
     * Enqueues a backfill as a background job. When {@code configKey} is set only
     * that one routing rule is considered; otherwise all matching rules run.
     * REPLACE cancels any in-flight backfill for the same scope first, and any
     * backfill running for a different scope is cancelled explicitly, so two
     * chains never race over the single {@link BackfillState}.
     */
    public static void enqueue(Context context, String configKey) {
        String runningScope = BackfillState.getScope(context);
        if (!sameScope(runningScope, configKey)) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(runningScope));
        }
        BackfillState.begin(context, configKey);
        WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName(configKey), ExistingWorkPolicy.REPLACE, buildRequest(configKey));
    }

    /** Cancels the active backfill for the scope (global when null/empty). */
    public static void cancel(Context context, String configKey) {
        BackfillState.cancel(context);
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(configKey));
    }

    private static OneTimeWorkRequest buildRequest(String configKey) {
        Data.Builder input = new Data.Builder();
        if (configKey != null) {
            input.putString(RequestWorker.DATA_CONFIG_KEY, configKey);
        }
        return new OneTimeWorkRequest.Builder(BackfillWorker.class)
                .setInputData(input.build())
                .addTag(TAG)
                .build();
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        String configKey = getInputData().getString(RequestWorker.DATA_CONFIG_KEY);

        try {
            String status = BackfillState.getStatus(context);
            if (BackfillState.STATUS_DONE.equals(status)
                    || BackfillState.STATUS_CANCELLED.equals(status)) {
                // A stale run (cancelled or replaced mid-chain); nothing to do.
                return Result.success();
            }
            // No scan result yet means this run is the scan pass.
            if (BackfillState.getTarget(context) < 0) {
                return scan(context, configKey);
            }
            return batch(context, configKey);
        } catch (Exception e) {
            Log.e("BackfillWorker", "backfill error: " + e);
            return Result.failure();
        }
    }

    // First run: count how many inbox messages have a FROM line matching any
    // scope rule. Counting on the sender alone skips the expensive per-message
    // regex/SIM checks, so the target (and a live counter) appear fast even on a
    // huge inbox; the full filter is applied per message during the batch pass.
    // Enqueues the first batch when anything matches.
    private Result scan(Context context, String configKey) {
        String asterisk = context.getString(R.string.asterisk);
        List<ForwardingConfig> configs = ruleScope(configKey);
        int matched = 0;
        int scanned = 0;

        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    Telephony.Sms.Inbox.CONTENT_URI, null, null, null, null);
            if (cursor == null) {
                return Result.failure();
            }
            int addressCol = cursor.getColumnIndex(Telephony.Sms.ADDRESS);
            if (addressCol < 0) {
                Log.e("BackfillWorker", "unexpected inbox columns");
                return Result.failure();
            }
            while (cursor.moveToNext()) {
                if (isStopped()) {
                    return Result.success();
                }
                String sender = cursor.getString(addressCol);
                if (sender == null || sender.isEmpty()) {
                    continue;
                }
                for (ForwardingConfig config : configs) {
                    if (config.getIsSmsEnabled()
                            && SmsBroadcastReceiver.matchesSender(config, sender, asterisk)) {
                        matched++;
                        break;
                    }
                }
                // Feed the progress bar a running count so "scanning" isn't a
                // silent indeterminate spinner on huge inboxes.
                if (++scanned % PROGRESS_REPORT_EVERY == 0) {
                    publishProgress(matched, -1);
                }
            }
        } catch (Exception e) {
            Log.e("BackfillWorker", "backfill scan error: " + e);
            return Result.failure();
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        if (isStopped()) {
            return Result.success();
        }
        BackfillState.setScanResult(context, matched);
        if (matched == 0) {
            BackfillState.complete(context, 0);
            publishProgress(0, 0);
            Log.d("BackfillWorker", "backfill: nothing matched");
            return Result.success();
        }
        publishProgress(0, matched);
        scheduleNext(context, configKey);
        return Result.success();
    }

    // One bounded pass over the inbox, ordered by id. Dispatches every message
    // that matches the scope, advances the persisted cursor, flushes the activity
    // log, and chains the next batch unless this was the last page.
    private Result batch(Context context, String configKey) {
        long lastId = BackfillState.getLastId(context);
        int target = BackfillState.getTarget(context);
        String asterisk = context.getString(R.string.asterisk);
        List<ForwardingConfig> configs = ruleScope(configKey);

        // Report the persisted count immediately so the progress bar never sits
        // on "scanning" while this batch runs; it refreshes again at the end.
        publishProgress(BackfillState.getDone(context), target);

        int dispatched = 0;
        long lastRowId = lastId;
        int rows = 0;
        List<ActivityLog.LogEntry> entries = new ArrayList<>();

        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    Telephony.Sms.Inbox.CONTENT_URI, null,
                    Telephony.Sms._ID + " > " + lastId, null,
                    Telephony.Sms._ID + " ASC");
            if (cursor == null) {
                return Result.failure();
            }
            int idCol = cursor.getColumnIndex(Telephony.Sms._ID);
            int addressCol = cursor.getColumnIndex(Telephony.Sms.ADDRESS);
            int bodyCol = cursor.getColumnIndex(Telephony.Sms.BODY);
            int dateCol = cursor.getColumnIndex(Telephony.Sms.DATE);
            if (idCol < 0 || addressCol < 0 || bodyCol < 0 || dateCol < 0) {
                Log.e("BackfillWorker", "unexpected inbox columns");
                return Result.failure();
            }
            while (rows < BATCH_SIZE && cursor.moveToNext()) {
                rows++;
                if (isStopped()) {
                    break;
                }
                long id = cursor.getLong(idCol);
                lastRowId = id;
                String sender = cursor.getString(addressCol);
                String content = cursor.getString(bodyCol);
                if (sender == null || sender.isEmpty() || content == null || content.isEmpty()) {
                    continue;
                }

                int slotId = readSimSlot(cursor);
                String slotName = slotId > 0 ? "sim" + slotId : "undetected";
                long timeStamp = cursor.getLong(dateCol);

                for (ForwardingConfig config : configs) {
                    if (!SmsBroadcastReceiver.matchesConfig(config, sender, asterisk, content, slotId)) {
                        continue;
                    }
                    RequestWorker.enqueue(context, SmsBroadcastReceiver.buildWebHookData(
                            config, sender, slotName, content, timeStamp));
                    entries.add(new ActivityLog.LogEntry(
                            config.getKey(), System.currentTimeMillis(),
                            ActivityLog.EVENT_BACKFILL, sender, content, null));
                    dispatched++;
                }
            }
        } catch (Exception e) {
            Log.e("BackfillWorker", "backfill error: " + e);
            return Result.failure();
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        // Persist before logging so a killed process resumes past this batch
        // rather than re-dispatching it.
        BackfillState.addProgress(context, dispatched, lastRowId);
        ActivityLog.logAll(context, entries);

        if (isStopped()) {
            return Result.success();
        }

        int done = BackfillState.getDone(context);
        if (rows < BATCH_SIZE) {
            BackfillState.complete(context, done);
            publishProgress(done, done);
            Log.d("BackfillWorker", "backfill done: " + done + " message(s)");
        } else {
            publishProgress(done, target);
            scheduleNext(context, configKey);
        }
        return Result.success();
    }

    // Chains one more batch onto the same unique work. APPEND makes it a child of
    // the currently running batch, so WorkManager runs it as soon as this one
    // finishes and cancels it together with the whole chain.
    private void scheduleNext(Context context, String configKey) {
        WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName(configKey), ExistingWorkPolicy.APPEND, buildRequest(configKey));
    }

    private void publishProgress(int done, int target) {
        setProgressAsync(new Data.Builder()
                .putInt(PROGRESS_DONE, done)
                .putInt(PROGRESS_TARGET, target)
                .build());
    }

    // Either every stored rule or just the one named in the worker input; empty
    // when the named rule was deleted before the worker ran.
    private List<ForwardingConfig> ruleScope(String configKey) {
        List<ForwardingConfig> all = ForwardingConfig.getAll(getApplicationContext());
        if (configKey == null) {
            return all;
        }
        List<ForwardingConfig> scoped = new ArrayList<>();
        for (ForwardingConfig config : all) {
            if (configKey.equals(config.getKey())) {
                scoped.add(config);
                break;
            }
        }
        return scoped;
    }

    // Best-effort SIM slot (1-based, 0 = unknown) so rules pinned to a specific
    // SIM are honored when the device exposes the column. Mirrors the heuristic
    // of SmsBroadcastReceiver.detectSim: only small values are trusted.
    private int readSimSlot(Cursor cursor) {
        for (String column : SIM_COLUMNS) {
            int index = cursor.getColumnIndex(column);
            if (index >= 0 && !cursor.isNull(index)) {
                int value = cursor.getInt(index);
                if (value >= 0 && value <= 2) {
                    return value + 1;
                }
            }
        }
        return 0;
    }
}
