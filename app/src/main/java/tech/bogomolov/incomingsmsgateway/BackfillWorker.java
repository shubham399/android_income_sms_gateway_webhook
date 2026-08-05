package tech.bogomolov.incomingsmsgateway;

import android.content.Context;
import android.database.Cursor;
import android.provider.Telephony;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.ArrayList;
import java.util.List;

/**
 * Backfills webhook forwarding: reads every message already in the SMS inbox
 * (not just ones arriving after install) and dispatches each through the same
 * per-rule matching and enqueue path as a live SMS. Triggered manually from the
 * main screen; requires {@code READ_SMS}. SIM slot is read from the provider
 * where the column exists and is best-effort otherwise — a rule pinned to a
 * specific SIM only matches when the slot can be determined.
 */
public class BackfillWorker extends Worker {

    // Columns probed for the SIM slot, in preference order. Not present on every
    // device; getColumnIndex returns -1 for a missing column without throwing.
    private static final String[] SIM_COLUMNS = {"sim_slot", "sub_id", "subscription"};

    public BackfillWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /** Enqueues the backfill as a one-off background job. */
    public static void enqueue(Context context) {
        OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(BackfillWorker.class).build();
        WorkManager.getInstance(context).enqueue(request);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        String asterisk = context.getString(R.string.asterisk);
        List<ForwardingConfig> configs = ForwardingConfig.getAll(context);

        int dispatched = 0;
        // Batched activity-log writes so a large inbox doesn't do a
        // SharedPreferences commit per message.
        List<ActivityLog.LogEntry> entries = new ArrayList<>();

        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    Telephony.Sms.Inbox.CONTENT_URI, null, null, null, null);
            if (cursor != null) {
                int addressCol = cursor.getColumnIndex(Telephony.Sms.ADDRESS);
                int bodyCol = cursor.getColumnIndex(Telephony.Sms.BODY);
                int dateCol = cursor.getColumnIndex(Telephony.Sms.DATE);
                if (addressCol < 0 || bodyCol < 0 || dateCol < 0) {
                    Log.e("BackfillWorker", "unexpected inbox columns");
                } else {
                    while (cursor.moveToNext()) {
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
                                    ActivityLog.EVENT_BACKFILL, sender, null));
                            dispatched++;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e("BackfillWorker", "backfill error: " + e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        ActivityLog.logAll(context, entries);
        Log.d("BackfillWorker", "dispatched " + dispatched + " backfill message(s)");
        return Result.success();
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
