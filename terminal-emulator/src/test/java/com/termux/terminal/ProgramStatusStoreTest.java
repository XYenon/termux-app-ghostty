package com.termux.terminal;

import org.junit.Test;

import java.util.List;

import static com.termux.terminal.ProgramStatusStore.*;
import static org.junit.Assert.*;

public class ProgramStatusStoreTest {
    private static Record report(String id, int state) {
        return new Record(state, KIND_NONE, -1, id, "", "", "");
    }

    @Test
    public void reportsReplaceAllFieldsAndSnapshotsAreIndependent() {
        ProgramStatusStore store = new ProgramStatusStore();
        store.update(new Record(BLOCKED, KIND_AUTH, 37, "", "agent", "Login", "Sign in"));
        List<Record> before = store.snapshot();
        store.update(report("", WORKING));
        Record record = store.snapshot().get(0);
        assertEquals(1, store.snapshot().size());
        assertEquals(WORKING, record.state);
        assertEquals(KIND_NONE, record.kind);
        assertEquals(-1, record.progress);
        assertEquals("", record.app);
        assertEquals("", record.title);
        assertEquals("", record.message);
        assertEquals(BLOCKED, before.get(0).state);
    }

    @Test
    public void clearMatchesPathSegmentsAndAllowsMissingParents() {
        ProgramStatusStore store = new ProgramStatusStore();
        store.update(report("build/test", WORKING));
        store.update(report("build/test/unit", DONE));
        store.update(report("builder", ERROR));
        store.update(report("build-test", IDLE));
        store.update(report("", BLOCKED));
        store.update(report("build", CLEAR));
        assertEquals(3, store.snapshot().size());
        assertEquals("builder", store.snapshot().get(0).id);
        assertEquals("build-test", store.snapshot().get(1).id);
        assertEquals("", store.snapshot().get(2).id);
        store.update(report("", CLEAR));
        assertTrue(store.snapshot().isEmpty());
    }

    @Test
    public void promptOrExitRemovesTransientStatesButKeepsUnseenResults() {
        ProgramStatusStore store = new ProgramStatusStore();
        for (int state = IDLE; state <= ERROR; state++)
            store.update(report("state" + state, state));
        assertTrue(store.endActivity());
        assertEquals(2, store.snapshot().size());
        assertEquals(DONE, store.snapshot().get(0).state);
        assertEquals(ERROR, store.snapshot().get(1).state);
        assertFalse(store.endActivity());
        store.update(report("new", WORKING));
        assertTrue(store.dismissFinished());
        assertEquals(1, store.snapshot().size());
        assertEquals("new", store.snapshot().get(0).id);
        assertFalse(store.dismissFinished());
    }

    @Test
    public void capacityEvictsLeastRecentlyUpdatedRatherThanOldestCreated() {
        ProgramStatusStore store = new ProgramStatusStore();
        for (int i = 0; i < 64; i++) store.update(report("job" + i, WORKING));
        assertEquals(64, store.snapshot().size());
        store.update(report("job0", WORKING));
        store.update(report("job64", BLOCKED));
        assertEquals(64, store.snapshot().size());
        assertEquals("job2", store.snapshot().get(0).id);
        assertEquals("job0", store.snapshot().get(62).id);
        assertEquals("job64", store.snapshot().get(63).id);
    }

    @Test
    public void snapshotsStayIndependentAcrossPromptDismissAndClear() {
        ProgramStatusStore store = new ProgramStatusStore();
        assertFalse(store.update(report("", CLEAR)));
        store.update(report("active", WORKING));
        store.update(report("finished", DONE));
        List<Record> original = store.snapshot();

        assertFalse(store.update(report("missing", CLEAR)));
        assertTrue(store.endActivity());
        assertEquals(1, store.snapshot().size());
        assertEquals("finished", store.snapshot().get(0).id);
        assertTrue(store.dismissFinished());
        assertTrue(store.snapshot().isEmpty());
        assertEquals(2, original.size());

        store.update(report("new", ERROR));
        List<Record> beforeClear = store.snapshot();
        assertTrue(store.update(report("", CLEAR)));
        assertTrue(store.snapshot().isEmpty());
        assertEquals("new", beforeClear.get(0).id);
    }

    @Test
    public void formatControlsAreRemovedWithoutLosingUnicodeOrTreatingTextAsMarkup() {
        Record record = new Record(BLOCKED, KIND_QUESTION, -1, "task", "agent",
            "\u202e确认\u2069", "<b>继续 😀?</b>\u200b");
        assertEquals("确认", record.title);
        assertEquals("<b>继续 😀?</b>", record.message);
    }
}
