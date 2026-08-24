package com.termux.shared.termux.terminal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

public class TermuxTerminalSessionClientBase implements TerminalSessionClient {

    public TermuxTerminalSessionClientBase() {
    }

    @Override
    public void onTextChanged(@NonNull TerminalSession changedSession) {
    }

    @Override
    public void onTitleChanged(@NonNull TerminalSession updatedSession) {
    }

    @Override
    public void onWorkingDirectoryChanged(@NonNull TerminalSession session,
                                          @Nullable String workingDirectory) {
    }

    @Override
    public void onMouseShapeChanged(@NonNull TerminalSession session, int shape) {
    }

    @Override
    public void onDesktopNotification(@NonNull TerminalSession session,
                                      @Nullable String title, @Nullable String body) {
    }

    @Override
    public void onProgressReport(@NonNull TerminalSession session, int state, int progress) {
    }

    @Override
    public void onSessionFinished(@NonNull TerminalSession finishedSession) {
    }

    @Override
    public void onCopyTextToClipboard(@NonNull TerminalSession session, String text) {
    }

    @Override
    @Deprecated
    public int onOscClipboard(@NonNull TerminalSession session, int location,
                              String mimeType, byte[] data, boolean clear) {
        return TerminalOutput.OSC_CLIPBOARD_RESULT_DENIED;
    }

    @Override
    public int onOscClipboard(@NonNull TerminalSession session, int location,
                              String[] mimeTypes, byte[][] data, boolean clear) {
        if (clear) return onOscClipboard(session, location,
            (String) null, (byte[]) null, true);
        if (mimeTypes == null || data == null || mimeTypes.length != 1 || data.length != 1)
            return TerminalOutput.OSC_CLIPBOARD_RESULT_UNSUPPORTED;
        return onOscClipboard(session, location, mimeTypes[0], data[0], false);
    }

    @Override
    public int onOscClipboardReadPermission(@NonNull TerminalSession session,
                                            String name, boolean granted,
                                            boolean canRemember) {
        return granted ? 1 : 0;
    }

    @Override
    public String[] onOscClipboardMimeTypes(@NonNull TerminalSession session, int location) {
        return new String[]{"text/plain"};
    }

    @Override
    @Deprecated
    public byte[] onOscClipboardRead(@NonNull TerminalSession session, int location) {
        return null;
    }

    @Override
    public byte[] onOscClipboardRead(@NonNull TerminalSession session, int location,
                                     String mimeType) {
        return "text/plain".equalsIgnoreCase(mimeType)
            ? onOscClipboardRead(session, location) : null;
    }

    @Override
    public void onOscClipboardReadComplete(@NonNull TerminalSession session) {
    }

    @Override
    public void onPasteTextFromClipboard(@Nullable TerminalSession session) {
    }

    @Override
    public void onBell(@NonNull TerminalSession session) {
    }

    @Override
    public void onColorsChanged(@NonNull TerminalSession changedSession) {
    }

    @Override
    public void onTerminalCursorStateChange(boolean state) {
    }

    @Override
    public void setTerminalShellPid(@NonNull TerminalSession session, int pid) {
    }


    @Override
    public Integer getTerminalCursorStyle() {
        return null;
    }



    @Override
    public void logError(String tag, String message) {
        Logger.logError(tag, message);
    }

    @Override
    public void logWarn(String tag, String message) {
        Logger.logWarn(tag, message);
    }

    @Override
    public void logInfo(String tag, String message) {
        Logger.logInfo(tag, message);
    }

    @Override
    public void logDebug(String tag, String message) {
        Logger.logDebug(tag, message);
    }

    @Override
    public void logVerbose(String tag, String message) {
        Logger.logVerbose(tag, message);
    }

    @Override
    public void logStackTraceWithMessage(String tag, String message, Exception e) {
        Logger.logStackTraceWithMessage(tag, message, e);
    }

    @Override
    public void logStackTrace(String tag, Exception e) {
        Logger.logStackTrace(tag, e);
    }

}
