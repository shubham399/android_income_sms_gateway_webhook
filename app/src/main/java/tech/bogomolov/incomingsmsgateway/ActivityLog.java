package tech.bogomolov.incomingsmsgateway;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-forwarding-rule delivery log. Records every dispatch step (queued,
 * delivered, retried, failed, backfilled) so the user can see what each routing
 * parameter has been doing. Each rule's entries live under its own
 * SharedPreferences key as a JSON array, newest first, capped at
 * {@link #MAX_PER_CONFIG} so the store can't grow without bound. Kept separate
 * from {@link ForwardingConfig} (whose getAll() would try to parse these as
 * configs) and from {@link FailedMessage} (which stores payloads, not a log).
 * Entries carry the sender and the message body, so the UI can show which SMS
 * was processed, alongside the event status.
 */
public class ActivityLog {

    private static final String PREFERENCE = "activity_log";

    // Oldest entries are dropped once this many are stored per rule.
    static final int MAX_PER_CONFIG = 200;

    public static final String EVENT_QUEUED = "queued";
    public static final String EVENT_SUCCESS = "success";
    public static final String EVENT_RETRY = "retry";
    public static final String EVENT_FAILED = "failed";
    public static final String EVENT_BACKFILL = "backfill";

    /** One logged event. Immutable plain holder for display in the UI. */
    public static class LogEntry {
        public final String configKey;
        public final long timestamp;
        public final String event;
        public final String sender;
        public final String content;
        public final String detail;
        // Monotonic write-order stamp so "newest on top" holds even when two
        // entries land in the same millisecond. 0 for entries stored before
        // this field existed (sorted by timestamp then).
        final long seq;

        public LogEntry(String configKey, long timestamp, String event, String sender,
                        String content, String detail) {
            this(configKey, timestamp, event, sender, content, detail, 0L);
        }

        public LogEntry(String configKey, long timestamp, String event, String sender,
                        String content, String detail, long seq) {
            this.configKey = configKey;
            this.timestamp = timestamp;
            this.event = event == null ? "" : event;
            this.sender = sender == null ? "" : sender;
            this.content = content == null ? "" : content;
            this.detail = detail == null ? "" : detail;
            this.seq = seq;
        }

        long sortKey() {
            return seq != 0L ? seq : timestamp;
        }

        JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("ts", this.timestamp);
                json.put("seq", this.seq);
                json.put("event", this.event);
                json.put("sender", this.sender);
                json.put("content", this.content);
                json.put("detail", this.detail);
            } catch (JSONException e) {
                Log.e("ActivityLog", String.valueOf(e.getMessage()));
            }
            return json;
        }

        static LogEntry fromJson(String configKey, JSONObject json) {
            return new LogEntry(
                    configKey,
                    json.optLong("ts", 0L),
                    json.optString("event", EVENT_QUEUED),
                    json.optString("sender", ""),
                    json.optString("content", ""),
                    json.optString("detail", ""),
                    json.optLong("seq", 0L));
        }
    }

    private static long lastSeq = 0L;

    private static long nextSeq() {
        // Strictly increasing within the process, and always ahead of any wall
        // clock, so a process restart can't collide with older stored entries.
        lastSeq = Math.max(System.currentTimeMillis(), lastSeq + 1);
        return lastSeq;
    }

    /** Logs a single event for one rule. */
    public static void log(Context context, String configKey, String event, String sender,
                           String content, String detail) {
        List<LogEntry> entries = new ArrayList<>();
        entries.add(new LogEntry(configKey, System.currentTimeMillis(), event, sender, content, detail));
        logAll(context, entries);
    }

    /**
     * Logs many events in one pass. Entries are grouped by rule and each rule's
     * stored array is read-modified once, so backfilling thousands of messages
     * doesn't do a SharedPreferences commit per message. New entries are
     * prepended (newest first).
     */
    public static void logAll(Context context, List<LogEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }

        SharedPreferences pref = getPreference(context);
        Map<String, List<LogEntry>> byRule = new LinkedHashMap<>();
        for (LogEntry entry : entries) {
            if (entry.configKey == null || entry.configKey.isEmpty()) {
                continue;
            }
            // Stamp a monotonic write-order seq so "newest on top" is
            // deterministic even for same-millisecond batches.
            entry = new LogEntry(entry.configKey, entry.timestamp, entry.event,
                    entry.sender, entry.content, entry.detail, nextSeq());
            List<LogEntry> bucket = byRule.get(entry.configKey);
            if (bucket == null) {
                bucket = new ArrayList<>();
                byRule.put(entry.configKey, bucket);
            }
            bucket.add(entry);
        }

        for (Map.Entry<String, List<LogEntry>> rule : byRule.entrySet()) {
            String configKey = rule.getKey();
            JSONArray merged = new JSONArray();
            for (LogEntry entry : rule.getValue()) {
                if (merged.length() >= MAX_PER_CONFIG) {
                    break;
                }
                merged.put(entry.toJson());
            }

            String stored = pref.getString(configKey, null);
            if (stored != null) {
                try {
                    JSONArray existing = new JSONArray(stored);
                    for (int i = 0; i < existing.length() && merged.length() < MAX_PER_CONFIG; i++) {
                        merged.put(existing.optJSONObject(i));
                    }
                } catch (JSONException e) {
                    Log.e("ActivityLog", String.valueOf(e.getMessage()));
                }
            }

            pref.edit().putString(configKey, merged.toString()).commit();
        }
    }

    /** Returns the stored entries for one rule, newest first. */
    public static List<LogEntry> getForConfig(Context context, String configKey) {
        List<LogEntry> result = new ArrayList<>();
        if (configKey == null) {
            return result;
        }
        String stored = getPreference(context).getString(configKey, null);
        if (stored == null) {
            return result;
        }
        try {
            JSONArray array = new JSONArray(stored);
            for (int i = 0; i < array.length(); i++) {
                result.add(LogEntry.fromJson(configKey, array.optJSONObject(i)));
            }
        } catch (JSONException e) {
            Log.e("ActivityLog", String.valueOf(e.getMessage()));
        }
        result.sort((a, b) -> Long.compare(b.sortKey(), a.sortKey()));
        return result;
    }

    /**
     * Returns every rule's entries merged into one list, newest first. The
     * time-based view shows all routing parameters together.
     */
    public static List<LogEntry> getAll(Context context) {
        List<LogEntry> result = new ArrayList<>();
        Map<String, ?> stored = getPreference(context).getAll();
        for (Map.Entry<String, ?> entry : stored.entrySet()) {
            if (!(entry.getValue() instanceof String)) {
                continue;
            }
            try {
                JSONArray array = new JSONArray((String) entry.getValue());
                for (int i = 0; i < array.length(); i++) {
                    result.add(LogEntry.fromJson(entry.getKey(), array.optJSONObject(i)));
                }
            } catch (JSONException e) {
                Log.e("ActivityLog", String.valueOf(e.getMessage()));
            }
        }
        result.sort((a, b) -> Long.compare(b.sortKey(), a.sortKey()));
        return result;
    }

    /** Number of rules that have log entries. */
    public static int getTotalCount(Context context) {
        return getPreference(context).getAll().size();
    }

    /** Drops the log for one rule. */
    public static void clearForConfig(Context context, String configKey) {
        if (configKey == null) {
            return;
        }
        SharedPreferences.Editor editor = getPreference(context).edit();
        editor.remove(configKey);
        editor.commit();
    }

    /** Drops every rule's log. */
    public static void clearAll(Context context) {
        SharedPreferences.Editor editor = getPreference(context).edit();
        editor.clear();
        editor.commit();
    }

    private static SharedPreferences getPreference(Context context) {
        return context.getSharedPreferences(PREFERENCE, Context.MODE_PRIVATE);
    }
}
