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
        ActivityLog.log(context, "rule-1", ActivityLog.EVENT_QUEUED, "+15551234567", null);
        ActivityLog.log(context, "rule-1", ActivityLog.EVENT_SUCCESS, "+15551234567", "HTTP 200");

        List<ActivityLog.LogEntry> entries = ActivityLog.getForConfig(context, "rule-1");
        assertEquals(2, entries.size());
        // Newest first.
        assertEquals(ActivityLog.EVENT_SUCCESS, entries.get(0).event);
        assertEquals(ActivityLog.EVENT_QUEUED, entries.get(1).event);
        assertEquals("+15551234567", entries.get(0).sender);
        assertEquals("HTTP 200", entries.get(0).detail);
        assertTrue(entries.get(0).timestamp >= entries.get(1).timestamp);
    }

    @Test
    public void testLogIsPerRule() {
        ActivityLog.log(context, "rule-1", ActivityLog.EVENT_QUEUED, "a", null);
        ActivityLog.log(context, "rule-2", ActivityLog.EVENT_FAILED, "b", "HTTP 500");

        assertEquals(1, ActivityLog.getForConfig(context, "rule-1").size());
        assertEquals(1, ActivityLog.getForConfig(context, "rule-2").size());
        assertEquals("b", ActivityLog.getForConfig(context, "rule-2").get(0).sender);
        assertEquals(2, ActivityLog.getTotalCount(context));
    }

    @Test
    public void testClearForConfig() {
        ActivityLog.log(context, "rule-1", ActivityLog.EVENT_QUEUED, "a", null);
        ActivityLog.log(context, "rule-2", ActivityLog.EVENT_QUEUED, "b", null);

        ActivityLog.clearForConfig(context, "rule-1");

        assertTrue(ActivityLog.getForConfig(context, "rule-1").isEmpty());
        assertEquals(1, ActivityLog.getForConfig(context, "rule-2").size());
    }

    @Test
    public void testNullConfigKeyIsIgnored() {
        ActivityLog.log(context, null, ActivityLog.EVENT_QUEUED, "a", null);
        assertEquals(0, ActivityLog.getTotalCount(context));
    }

    @Test
    public void testLogAllBatchesNewestFirst() {
        List<ActivityLog.LogEntry> batch = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            batch.add(new ActivityLog.LogEntry(
                    "rule-1", 1000L + i, ActivityLog.EVENT_QUEUED, "s" + i, null));
        }
        ActivityLog.logAll(context, batch);

        List<ActivityLog.LogEntry> entries = ActivityLog.getForConfig(context, "rule-1");
        assertEquals(10, entries.size());
        // Last added is first.
        assertEquals("s9", entries.get(0).sender);
        assertEquals("s0", entries.get(9).sender);
    }

    @Test
    public void testLogAllAppendsToExisting() {
        ActivityLog.log(context, "rule-1", ActivityLog.EVENT_QUEUED, "old", null);

        List<ActivityLog.LogEntry> batch = new ArrayList<>();
        batch.add(new ActivityLog.LogEntry("rule-1", 2000L, ActivityLog.EVENT_SUCCESS, "new", "HTTP 200"));
        ActivityLog.logAll(context, batch);

        List<ActivityLog.LogEntry> entries = ActivityLog.getForConfig(context, "rule-1");
        assertEquals(2, entries.size());
        assertEquals("new", entries.get(0).sender);
        assertEquals("old", entries.get(1).sender);
    }

    @Test
    public void testPerRuleCap() {
        for (int i = 0; i < ActivityLog.MAX_PER_CONFIG + 10; i++) {
            ActivityLog.log(context, "rule-1", ActivityLog.EVENT_QUEUED, "s" + i, null);
        }
        assertEquals(ActivityLog.MAX_PER_CONFIG,
                ActivityLog.getForConfig(context, "rule-1").size());
    }
}
