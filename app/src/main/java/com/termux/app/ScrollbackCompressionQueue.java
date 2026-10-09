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
        int count = Math.min(Math.max(0, terminalLimit), queued.size());
        List<Target<T>> batch = new ArrayList<>(queued.subList(0, count));
        queued.subList(0, count).clear();
        batch.removeIf(target -> {
            for (int step = 0; step < stepsPerTerminal; step++) {
                if (compress.applyAsInt(target.terminal) != RESULT_PENDING) return true;
            }
            return stepsPerTerminal <= 0;
        });
        queued.addAll(batch);
    }

    boolean hasWork() {
        return !queued.isEmpty();
    }

    int size() {
        return queued.size();
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
