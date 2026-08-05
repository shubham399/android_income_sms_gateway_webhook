package tech.bogomolov.incomingsmsgateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * Instrumented tests for {@link ActivityLog}: the log/get/clear round-trip, rule
 * isolation, newest-first ordering, batch writes, and the per-rule size cap.
 * Needs a real SharedPreferences, so it runs on a device/emulator.
 */
@RunWith(AndroidJUnit4.class)
public class ActivityLogTest {

    private final Context context =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Before
    public void setup() {
        ActivityLog.clearAll(context);
    }

    @After
    public void tearDown() {
        ActivityLog.clearAll(context);
    }

    @Test
    public void testLogAndGetForConfig() {
        ActivityLog.log(context, "rule-1", ActivityLog.EVENT_QUEUED, "+15551234567", "Your OTP is 1234", null);
        ActivityLog.log(context, "rule-1", ActivityLog.EVENT_SUCCESS, "+15551234567", "Your OTP is 1234", "HTTP 200");

        List<ActivityLog.LogEntry> entries = ActivityLog.getForConfig(context, "rule-1");
        assertEquals(2, entries.size());
        // Newest first.
        assertEquals(ActivityLog.EVENT_SUCCESS, entries.get(0).event);
        assertEquals(ActivityLog.EVENT_QUEUED, entries.get(1).event);
        assertEquals("+15551234567", entries.get(0).sender);
        // The SMS body round-trips so the UI can show which message was processed.
        assertEquals("Your OTP is 1234", entries.get(0).content);
        assertEquals("HTTP 200", entries.get(0).detail);
        assertTrue(entries.get(0).timestamp >= entries.get(1).timestamp);
    }

    @Test
    public void testLogIsPerRule() {
        ActivityLog.log(context, "rule-1", ActivityLog.EVENT_QUEUED, "a", "body-a", null);
        ActivityLog.log(context, "rule-2", ActivityLog.EVENT_FAILED, "b", "body-b", "HTTP 500");

        assertEquals(1, ActivityLog.getForConfig(context, "rule-1").size());
        assertEquals(1, ActivityLog.getForConfig(context, "rule-2").size());
        assertEquals("b", ActivityLog.getForConfig(context, "rule-2").get(0).sender);
        assertEquals("body-b", ActivityLog.getForConfig(context, "rule-2").get(0).content);
        assertEquals(2, ActivityLog.getTotalCount(context));
    }

    @Test
    public void testClearForConfig() {
        ActivityLog.log(context, "rule-1", ActivityLog.EVENT_QUEUED, "a", "body-a", null);
        ActivityLog.log(context, "rule-2", ActivityLog.EVENT_QUEUED, "b", "body-b", null);

        ActivityLog.clearForConfig(context, "rule-1");

        assertTrue(ActivityLog.getForConfig(context, "rule-1").isEmpty());
        assertEquals(1, ActivityLog.getForConfig(context, "rule-2").size());
    }

    @Test
    public void testNullConfigKeyIsIgnored() {
        ActivityLog.log(context, null, ActivityLog.EVENT_QUEUED, "a", "body-a", null);
        assertEquals(0, ActivityLog.getTotalCount(context));
    }

    @Test
    public void testGetAllMergesRulesNewestFirst() {
        List<ActivityLog.LogEntry> batch = new ArrayList<>();
        batch.add(new ActivityLog.LogEntry("rule-1", 1000L, ActivityLog.EVENT_QUEUED, "a", "body-a", null));
        batch.add(new ActivityLog.LogEntry("rule-2", 3000L, ActivityLog.EVENT_FAILED, "b", "body-b", "HTTP 500"));
        batch.add(new ActivityLog.LogEntry("rule-1", 2000L, ActivityLog.EVENT_SUCCESS, "a", "body-a", "HTTP 200"));
        batch.add(new ActivityLog.LogEntry("rule-2", 4000L, ActivityLog.EVENT_RETRY, "b", "body-b", "timeout"));
        ActivityLog.logAll(context, batch);

        List<ActivityLog.LogEntry> all = ActivityLog.getAll(context);
        assertEquals(4, all.size());
        // Newest first across every rule, regardless of which rule logged it.
        assertEquals(4000L, all.get(0).timestamp);
        assertEquals("rule-2", all.get(0).configKey);
        assertEquals(3000L, all.get(1).timestamp);
        assertEquals(2000L, all.get(2).timestamp);
        assertEquals(1000L, all.get(3).timestamp);
        // Entry carries its rule so the row can label which parameter it belongs to.
        assertEquals("body-a", all.get(3).content);
    }

    @Test
    public void testGetAllTieBreaksByWriteOrder() {
        // Same timestamp, same rule: the later write must still surface first.
        ActivityLog.log(context, "rule-1", ActivityLog.EVENT_QUEUED, "a", "body-a", null);
        List<ActivityLog.LogEntry> second = new ArrayList<>();
        second.add(new ActivityLog.LogEntry("rule-1", 9999L, ActivityLog.EVENT_SUCCESS, "a", "body-a", "HTTP 200"));
        ActivityLog.logAll(context, second);

        List<ActivityLog.LogEntry> all = ActivityLog.getAll(context);
        assertEquals(2, all.size());
        assertEquals(ActivityLog.EVENT_SUCCESS, all.get(0).event);
        assertEquals(ActivityLog.EVENT_QUEUED, all.get(1).event);
    }

    @Test
    public void testLogAllBatchesNewestFirst() {
        List<ActivityLog.LogEntry> batch = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            batch.add(new ActivityLog.LogEntry(
                    "rule-1", 1000L + i, ActivityLog.EVENT_QUEUED, "s" + i, "body-" + i, null));
        }
        ActivityLog.logAll(context, batch);

        List<ActivityLog.LogEntry> entries = ActivityLog.getForConfig(context, "rule-1");
        assertEquals(10, entries.size());
        // Last added is first.
        assertEquals("s9", entries.get(0).sender);
        assertEquals("body-9", entries.get(0).content);
        assertEquals("s0", entries.get(9).sender);
    }

    @Test
    public void testLogAllAppendsToExisting() {
        ActivityLog.log(context, "rule-1", ActivityLog.EVENT_QUEUED, "old", "old-body", null);

        List<ActivityLog.LogEntry> batch = new ArrayList<>();
        batch.add(new ActivityLog.LogEntry("rule-1", 2000L, ActivityLog.EVENT_SUCCESS, "new", "new-body", "HTTP 200"));
        ActivityLog.logAll(context, batch);

        List<ActivityLog.LogEntry> entries = ActivityLog.getForConfig(context, "rule-1");
        assertEquals(2, entries.size());
        assertEquals("new", entries.get(0).sender);
        assertEquals("new-body", entries.get(0).content);
        assertEquals("old", entries.get(1).sender);
    }

    @Test
    public void testPerRuleCap() {
        for (int i = 0; i < ActivityLog.MAX_PER_CONFIG + 10; i++) {
            ActivityLog.log(context, "rule-1", ActivityLog.EVENT_QUEUED, "s" + i, "body-" + i, null);
        }
        assertEquals(ActivityLog.MAX_PER_CONFIG,
                ActivityLog.getForConfig(context, "rule-1").size());
    }
}
