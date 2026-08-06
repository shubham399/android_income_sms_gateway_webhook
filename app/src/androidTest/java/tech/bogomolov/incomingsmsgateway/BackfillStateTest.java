package tech.bogomolov.incomingsmsgateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Instrumented tests for {@link BackfillState}: the begin/scan/progress/complete/
 * cancel lifecycle and the progress math the UI shows. Needs a real
 * SharedPreferences, so it runs on a device/emulator.
 */
@RunWith(AndroidJUnit4.class)
public class BackfillStateTest {

    private final Context context =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Before
    public void setup() {
        BackfillState.reset(context);
    }

    @After
    public void tearDown() {
        BackfillState.reset(context);
    }

    @Test
    public void testIdleByDefault() {
        assertEquals(BackfillState.STATUS_IDLE, BackfillState.getStatus(context));
        assertEquals(-1, BackfillState.getTarget(context));
        assertEquals(0, BackfillState.getDone(context));
        assertFalse(BackfillState.isActive(context));
    }

    @Test
    public void testBeginResetsProgress() {
        BackfillState.begin(context, "rule-1");
        assertEquals("rule-1", BackfillState.getScope(context));
        assertEquals(BackfillState.STATUS_SCANNING, BackfillState.getStatus(context));
        assertEquals(-1, BackfillState.getTarget(context));
        assertEquals(0, BackfillState.getDone(context));
        assertEquals(0L, BackfillState.getLastId(context));
        assertTrue(BackfillState.isActive(context));
    }

    @Test
    public void testBeginWithNullScopeIsGlobal() {
        BackfillState.begin(context, null);
        assertEquals("", BackfillState.getScope(context));
        assertTrue(BackfillState.isActive(context));
    }

    @Test
    public void testScanResultStartsRunning() {
        BackfillState.begin(context, null);
        BackfillState.setScanResult(context, 5);
        assertEquals(BackfillState.STATUS_RUNNING, BackfillState.getStatus(context));
        assertEquals(5, BackfillState.getTarget(context));
        assertEquals(0, BackfillState.getDone(context));
        assertTrue(BackfillState.isActive(context));
    }

    @Test
    public void testAddProgressAccumulates() {
        BackfillState.begin(context, null);
        BackfillState.setScanResult(context, 10);
        BackfillState.addProgress(context, 3, 100L);
        BackfillState.addProgress(context, 2, 250L);
        assertEquals(5, BackfillState.getDone(context));
        assertEquals(250L, BackfillState.getLastId(context));
    }

    @Test
    public void testAddProgressKeepsHighestCursor() {
        BackfillState.begin(context, null);
        BackfillState.setScanResult(context, 10);
        BackfillState.addProgress(context, 3, 250L);
        BackfillState.addProgress(context, 2, 100L);
        assertEquals(5, BackfillState.getDone(context));
        assertEquals(250L, BackfillState.getLastId(context));
    }

    @Test
    public void testCompleteSnapsProgressToDone() {
        BackfillState.begin(context, null);
        BackfillState.setScanResult(context, 10);
        BackfillState.addProgress(context, 6, 600L);
        BackfillState.complete(context, 6);
        assertEquals(BackfillState.STATUS_DONE, BackfillState.getStatus(context));
        assertEquals(6, BackfillState.getTarget(context));
        assertEquals(6, BackfillState.getDone(context));
        assertFalse(BackfillState.isActive(context));
    }

    @Test
    public void testCancelIsTerminal() {
        BackfillState.begin(context, null);
        assertTrue(BackfillState.isActive(context));
        BackfillState.cancel(context);
        assertEquals(BackfillState.STATUS_CANCELLED, BackfillState.getStatus(context));
        assertFalse(BackfillState.isActive(context));
    }
}
