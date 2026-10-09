package com.termux.terminal;

import java.nio.charset.StandardCharsets;

/** A client which receives callbacks from events triggered by feeding input to a {@link TerminalEmulator}. */
public abstract class TerminalOutput {

    public static final int OSC_CLIPBOARD_RESULT_SUCCESS = 0;
    public static final int OSC_CLIPBOARD_RESULT_DENIED = 1;
    public static final int OSC_CLIPBOARD_RESULT_UNSUPPORTED = 2;
    public static final int OSC_CLIPBOARD_RESULT_BUSY = 3;
    public static final int OSC_CLIPBOARD_RESULT_INVALID_DATA = 4;
    public static final int OSC_CLIPBOARD_RESULT_IO_ERROR = 5;

    /** Write a string using the UTF-8 encoding to the terminal client. */
    public final void write(String data) {
        if (data == null) return;
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        write(bytes, 0, bytes.length);
    }

    /** Write bytes to the terminal client. */
    public abstract void write(byte[] data, int offset, int count);

    /** Notify the terminal client that the terminal title has changed. */
    public abstract void titleChanged(String oldTitle, String newTitle);

    /** Notify the host that an OSC working-directory report changed. */
    public abstract void workingDirectoryChanged(String workingDirectory);

    /** Notify the host that OSC 22 changed the requested pointer shape. */
    public abstract void onMouseShapeChanged(int shape);

    /** Request an OSC 9/777 desktop notification. */
    public abstract void onDesktopNotification(String title, String body);

    /** Report an OSC 9;4 progress state and percentage, or -1 if omitted. */
    public abstract void onProgressReport(int state, int progress);

    /** Receive a validated OSC 7501 report with decoded UTF-8 text. */
    public void onProgramStatusReport(int state, int kind, int progress,
                                      String id, String app, String title, String message) {
    }

    /** A fresh OSC 133 shell prompt ends transient program status records. */
    public void onProgramStatusPrompt() {
    }

    /** Notify the terminal client that text should be copied to clipboard. */
    public abstract void onCopyTextToClipboard(String text);

    /** Apply decoded OSC clipboard representations to the host clipboard. */
    public abstract int onOscClipboard(int location, String[] mimeTypes,
                                       byte[][] data, boolean clear);

    /** Ask whether a terminal program may read the host clipboard. */
    public int onOscClipboardReadPermission(String name, boolean granted,
                                             boolean canRemember) {
        return granted ? 1 : 0;
    }

    /** List MIME representations available from the host clipboard. */
    public String[] onOscClipboardMimeTypes(int location) {
        return null;
    }

    /** Read one MIME representation from the host clipboard. */
    public abstract byte[] onOscClipboardRead(int location, String mimeType);

    /** Release the clipboard snapshot used by the current OSC read. */
    public void onOscClipboardReadComplete() {
    }

    /** Notify the terminal client that text should be pasted from clipboard. */
    public abstract void onPasteTextFromClipboard();

    /** Notify the terminal client that a bell character (ASCII 7, bell, BEL, \a, ^G)) has been received. */
    public abstract void onBell();

    public abstract void onColorsChanged();

}
