package com.termux.app;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToIntFunction;

/** Small pressure-event work queue; memory sizes are measured only when entries are added. */
final class ScrollbackCompressionQueue<T> {
    static final int RESULT_PENDING = 1;

    private final List<Target<T>> queued = new ArrayList<>();

    void add(T terminal, long residentBytes, boolean supported) {
        if (supported && residentBytes > 0) {
            queued.add(new Target<>(terminal, residentBytes));
        }
    }

    void sortLargestFirst() {
        queued.sort(Comparator.comparingLong(
            (Target<T> target) -> target.residentBytes).reversed());
    }

    /** Process one bounded batch and retain only terminals reporting pending work. */
    void processBatch(int terminalLimit, int stepsPerTerminal,
                      ToIntFunction<T> compress) {
        List<Target<T>> batch = takeBatch(terminalLimit);
        List<Target<T>> pending = new ArrayList<>();
        for (Target<T> target : batch) {
            for (int step = 0; step < Math.max(0, stepsPerTerminal); step++) {
                int result = compress.applyAsInt(target.terminal);
                if (result != RESULT_PENDING) break;
                if (step == stepsPerTerminal - 1) pending.add(target);
            }
        }
        queued.addAll(pending);
    }

    boolean hasWork() {
        return !queued.isEmpty();
    }

    int size() {
        return queued.size();
    }

    private List<Target<T>> takeBatch(int limit) {
        int count = Math.min(Math.max(0, limit), queued.size());
        List<Target<T>> batch = new ArrayList<>(queued.subList(0, count));
        queued.subList(0, count).clear();
        return batch;
    }

    static final class Target<T> {
        final T terminal;
        final long residentBytes;

        Target(T terminal, long residentBytes) {
            this.terminal = terminal;
            this.residentBytes = residentBytes;
        }
    }
}
