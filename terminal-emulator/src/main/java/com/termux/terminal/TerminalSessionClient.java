package com.termux.terminal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The interface for communication between {@link TerminalSession} and its client. It is used to
 * send callbacks to the client when {@link TerminalSession} changes or for sending other
 * back data to the client like logs.
 */
public interface TerminalSessionClient {

    void onTextChanged(@NonNull TerminalSession changedSession);

    void onTitleChanged(@NonNull TerminalSession changedSession);

    void onWorkingDirectoryChanged(@NonNull TerminalSession changedSession,
                                   @Nullable String workingDirectory);

    void onMouseShapeChanged(@NonNull TerminalSession changedSession, int shape);

    void onDesktopNotification(@NonNull TerminalSession session,
                               @Nullable String title, @Nullable String body);

    void onProgressReport(@NonNull TerminalSession session, int state, int progress);

    void onSessionFinished(@NonNull TerminalSession finishedSession);

    void onCopyTextToClipboard(@NonNull TerminalSession session, String text);

    @Deprecated
    int onOscClipboard(@NonNull TerminalSession session, int location,
                       String mimeType, byte[] data, boolean clear);

    default int onOscClipboard(@NonNull TerminalSession session, int location,
                               String[] mimeTypes, byte[][] data, boolean clear) {
        if (clear) return onOscClipboard(session, location,
            (String) null, (byte[]) null, true);
        if (mimeTypes == null || data == null || mimeTypes.length != 1 || data.length != 1)
            return TerminalOutput.OSC_CLIPBOARD_RESULT_UNSUPPORTED;
        return onOscClipboard(session, location, mimeTypes[0], data[0], false);
    }

    default int onOscClipboardReadPermission(@NonNull TerminalSession session,
                                             String name, boolean granted,
                                             boolean canRemember) {
        return granted ? 1 : 0;
    }

    default String[] onOscClipboardMimeTypes(@NonNull TerminalSession session, int location) {
        return new String[]{"text/plain"};
    }

    @Deprecated
    byte[] onOscClipboardRead(@NonNull TerminalSession session, int location);

    default byte[] onOscClipboardRead(@NonNull TerminalSession session, int location,
                                      String mimeType) {
        return "text/plain".equalsIgnoreCase(mimeType)
            ? onOscClipboardRead(session, location) : null;
    }

    default void onOscClipboardReadComplete(@NonNull TerminalSession session) {
    }

    void onPasteTextFromClipboard(@Nullable TerminalSession session);

    void onBell(@NonNull TerminalSession session);

    void onColorsChanged(@NonNull TerminalSession session);

    void onTerminalCursorStateChange(boolean state);

    void setTerminalShellPid(@NonNull TerminalSession session, int pid);



    Integer getTerminalCursorStyle();



    void logError(String tag, String message);

    void logWarn(String tag, String message);

    void logInfo(String tag, String message);

    void logDebug(String tag, String message);

    void logVerbose(String tag, String message);

    void logStackTraceWithMessage(String tag, String message, Exception e);

    void logStackTrace(String tag, Exception e);

}
