package com.termux.app;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.ToIntFunction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ScrollbackCompressionQueueTest {
    private static final int COMPLETE = 2;
    private static final int PENDING = ScrollbackCompressionQueue.RESULT_PENDING;
    private static final int UNSUPPORTED = 0;

    @Test
    public void skipsUnsupportedAndEmptyTerminals() {
        ScrollbackCompressionQueue<String> queue = new ScrollbackCompressionQueue<>();
        queue.add("unsupported", 900, false);
        queue.add("empty", 0, true);
        queue.add("eligible", 100, true);
        List<String> calls = new ArrayList<>();

        queue.sortLargestFirst();
        queue.processBatch(4, 2, terminal -> {
            calls.add(terminal);
            return COMPLETE;
        });

        assertEquals(Arrays.asList("eligible"), calls);
        assertFalse(queue.hasWork());
    }

    @Test
    public void moreThanFourCompleteTargetsFinishWithoutContinuation() {
        ScrollbackCompressionQueue<String> queue = new ScrollbackCompressionQueue<>();
        List<String> calls = new ArrayList<>();
        for (int i = 0; i < 9; i++) queue.add("terminal-" + i, 100 - i, true);
        queue.sortLargestFirst();
        ToIntFunction<String> complete = terminal -> {
            calls.add(terminal);
            return COMPLETE;
        };

        queue.processBatch(4, 2, complete);
        assertTrue(queue.hasWork()); // Five terminals remain for a later batch.
        queue.processBatch(4, 2, complete);
        queue.processBatch(4, 2, complete);

        assertEquals(9, calls.size());
        assertFalse(queue.hasWork());
    }

    @Test
    public void closedAndUnsupportedResultsAreDropped() {
        ScrollbackCompressionQueue<String> queue = new ScrollbackCompressionQueue<>();
        queue.add("closed", 200, true);
        queue.add("unsupported-after-query", 100, true);
        queue.sortLargestFirst();

        queue.processBatch(4, 3, terminal -> UNSUPPORTED);

        assertFalse(queue.hasWork());
    }

    @Test
    public void pendingWorkRetriesAfterOtherTargetsAndCallsStayBounded() {
        ScrollbackCompressionQueue<String> queue = new ScrollbackCompressionQueue<>();
        List<String> calls = new ArrayList<>();
        queue.add("largest-pending", 300, true);
        queue.add("smaller-complete", 100, true);
        queue.sortLargestFirst();

        queue.processBatch(1, 2, terminal -> {
            calls.add(terminal);
            return PENDING;
        });
        assertEquals(Arrays.asList("largest-pending", "largest-pending"), calls);
        assertTrue(queue.hasWork());

        queue.processBatch(1, 2, terminal -> {
            calls.add(terminal);
            return COMPLETE;
        });
        assertEquals(3, calls.size());
        assertEquals("smaller-complete", calls.get(2));
        assertTrue(queue.hasWork()); // The pending target was rotated behind it.

        queue.processBatch(1, 2, terminal -> {
            calls.add(terminal);
            return COMPLETE;
        });
        assertFalse(queue.hasWork());
        assertEquals(4, calls.size());
    }

    @Test
    public void onePendingStepThenCompleteDoesNotRequeue() {
        ScrollbackCompressionQueue<String> queue = new ScrollbackCompressionQueue<>();
        queue.add("target", 10, true);
        queue.processBatch(4, 3, new ToIntFunction<String>() {
            private int calls;
            @Override
            public int applyAsInt(String terminal) {
                return ++calls == 1 ? PENDING : COMPLETE;
            }
        });

        assertFalse(queue.hasWork());
    }
}
