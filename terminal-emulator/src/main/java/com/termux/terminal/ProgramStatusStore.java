package com.termux.terminal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/** Per-session OSC 7501 records. Accessed on the session's main thread. */
public final class ProgramStatusStore {
    // Values match GhosttyProgramStatusState and GhosttyProgramStatusKind.
    public static final int IDLE = 0, WORKING = 1, DONE = 2, BLOCKED = 3,
        ERROR = 4, CLEAR = 5;
    public static final int KIND_NONE = 0, KIND_PERMISSION = 1,
        KIND_QUESTION = 2, KIND_AUTH = 3;
    private static final int MAX_RECORDS = 64;

    public static final class Record {
        public final int state, kind, progress;
        public final String id, app, title, message;

        public Record(int state, int kind, int progress, String id, String app,
                      String title, String message) {
            this.state = state;
            this.kind = kind;
            this.progress = progress;
            this.id = id;
            this.app = app;
            this.title = displayText(title);
            this.message = displayText(message);
        }

        private static String displayText(String text) {
            StringBuilder result = new StringBuilder(text.length());
            text.codePoints().filter(cp -> Character.getType(cp) != Character.FORMAT)
                .forEach(result::appendCodePoint);
            return result.toString();
        }
    }

    private final LinkedHashMap<String, Record> records = new LinkedHashMap<>();

    public void update(Record report) {
        if (report.state == CLEAR) {
            if (report.id.isEmpty()) records.clear();
            else records.keySet().removeIf(id -> id.equals(report.id) ||
                id.startsWith(report.id + "/"));
            return;
        }
        // Reinsert to track update order, rather than access or creation order.
        records.remove(report.id);
        records.put(report.id, report);
        if (records.size() > MAX_RECORDS)
            records.remove(records.keySet().iterator().next());
    }

    /** A fresh shell prompt or process exit ends transient program activity. */
    public boolean endActivity() {
        return records.values().removeIf(record -> record.state == IDLE ||
            record.state == WORKING || record.state == BLOCKED);
    }

    /** Acknowledge results only when the user explicitly dismisses them. */
    public boolean dismissFinished() {
        return records.values().removeIf(record -> record.state == DONE ||
            record.state == ERROR);
    }

    /** Oldest update first; callers cannot mutate the stored records. */
    public List<Record> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(records.values()));
    }
}
