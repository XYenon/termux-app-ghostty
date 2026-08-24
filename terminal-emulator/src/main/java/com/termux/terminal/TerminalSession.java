package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Message;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A terminal session, consisting of a process coupled to a terminal interface.
 * <p>
 * The subprocess will be executed by the constructor, and when the size is made known by a call to
 * {@link #updateSize(int, int, int, int)} terminal emulation will begin and threads will be spawned to handle the subprocess I/O.
 * All terminal emulation and callback methods will be performed on the main thread.
 * <p>
 * The child process may be exited forcefully by using the {@link #finishIfRunning()} method.
 * <p>
 * NOTE: The terminal session may outlive the EmulatorView, so be careful with callbacks!
 */
public final class TerminalSession extends TerminalOutput {

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_PROCESS_EXITED = 4;
    /** Large enough to preserve typical full-screen TUI writes in one feed. */
    private static final int PROCESS_INPUT_BUFFER_SIZE = 64 * 1024;
    /** Bound pending PTY writes while still allowing large responses to stream. */
    private static final int PROCESS_OUTPUT_BUFFER_SIZE = 64 * 1024;

    public final String mHandle = UUID.randomUUID().toString();

    GhosttyTerminal mTerminal;

    /**
     * A queue written to from a separate thread when the process outputs, and read by main thread to process by
     * terminal emulator.
     */
    final ByteQueue mProcessToTerminalIOQueue =
        new ByteQueue(PROCESS_INPUT_BUFFER_SIZE);
    private final AtomicBoolean mProcessInputPending = new AtomicBoolean();
    /** Bounded queue for ordered writes to the terminal process. */
    final ByteQueue mTerminalToProcessIOQueue =
        new ByteQueue(PROCESS_OUTPUT_BUFFER_SIZE);
    private final Object mTerminalWriteLock = new Object();
    /** Buffer used to encode code points as UTF-8 before queuing them. */
    private final byte[] mUtf8InputBuffer = new byte[5];
    private boolean mCloseRequested;
    private boolean mClosed;

    /** Callback which gets notified when a session finishes or changes title. */
    TerminalSessionClient mClient;

    /** The pid of the shell process. 0 if not started and -1 if finished running. */
    int mShellPid;

    /** The exit status of the shell process. Only valid if ${@link #mShellPid} is -1. */
    int mShellExitStatus;

    /**
     * The file descriptor referencing the master half of a pseudo-terminal pair, resulting from calling
     * {@link JNI#createSubprocess(String, String, String[], String[], int[], int, int, int, int)}.
     */
    private int mTerminalFileDescriptor = -1;
    private Integer mDeferredProcessExitStatus;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler();

    private final String mShellPath;
    private final String mCwd;
    private final String[] mArgs;
    private final String[] mEnv;
    private final Integer mTranscriptRows;


    private static final String LOG_TAG = "TerminalSession";

    public TerminalSession(String shellPath, String cwd, String[] args, String[] env, Integer transcriptRows, TerminalSessionClient client) {
        this.mShellPath = shellPath;
        this.mCwd = cwd;
        this.mArgs = args;
        this.mEnv = env;
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;
    }

    /** Inform the attached pty of the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        synchronized (this) {
            if (mCloseRequested) return;
        }
        if (mTerminal == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels);
        } else {
            JNI.setPtyWindowSize(mTerminalFileDescriptor, rows, columns, cellWidthPixels, cellHeightPixels);
            mTerminal.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mTerminal == null) ? null : mTerminal.getTitle();
    }

    /**
     * Set the terminal emulator's window size and start terminal emulation.
     *
     * @param columns The number of columns in the terminal window.
     * @param rows    The number of rows in the terminal window.
     */
    public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        int transcriptRows = mTranscriptRows == null
            ? TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS
            : mTranscriptRows;
        mTerminal = new GhosttyTerminal(this, columns, rows, cellWidthPixels,
            cellHeightPixels, transcriptRows);
        mTerminal.setColorScheme(TerminalColors.COLOR_SCHEME.copyColors());

        int[] processId = new int[1];
        mTerminalFileDescriptor = JNI.createSubprocess(mShellPath, mCwd, mArgs, mEnv, processId, rows, columns, cellWidthPixels, cellHeightPixels);
        mShellPid = processId[0];
        mClient.setTerminalShellPid(this, mShellPid);

        final FileDescriptor terminalFileDescriptorWrapped = wrapFileDescriptor(mTerminalFileDescriptor, mClient);

        new Thread("TermSessionInputReader[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                try (InputStream termIn = new FileInputStream(terminalFileDescriptorWrapped)) {
                    final byte[] buffer = new byte[PROCESS_INPUT_BUFFER_SIZE];
                    while (true) {
                        int read = termIn.read(buffer);
                        if (read == -1) return;
                        if (!mProcessToTerminalIOQueue.write(buffer, 0, read)) return;
                        if (mProcessInputPending.compareAndSet(false, true))
                            mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
                    }
                } catch (Exception e) {
                    // Ignore, just shutting down.
                }
            }
        }.start();

        new Thread("TermSessionOutputWriter[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                try (FileOutputStream termOut = new FileOutputStream(terminalFileDescriptorWrapped)) {
                    byte[] buffer = new byte[PROCESS_OUTPUT_BUFFER_SIZE];
                    while (true) {
                        int read = mTerminalToProcessIOQueue.read(buffer, true);
                        if (read < 0) return;
                        termOut.write(buffer, 0, read);
                    }
                } catch (IOException e) {
                    // Ignore.
                } finally {
                    mTerminalToProcessIOQueue.close();
                }
            }
        }.start();

        new Thread("TermSessionWaiter[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                int processExitCode = JNI.waitFor(mShellPid);
                mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_PROCESS_EXITED, processExitCode));
            }
        }.start();

    }

    /** Write data to the shell process. */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (mShellPid > 0 && count > 0) {
            synchronized (mTerminalWriteLock) {
                mTerminalToProcessIOQueue.write(data, offset, count);
            }
        }
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public GhosttyTerminal getTerminal() {
        return mTerminal;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        mTerminal.reset();
        notifyScreenUpdate();
    }

    /** Finish this terminal session by sending SIGKILL to the shell. */
    public void finishIfRunning() {
        if (isRunning()) {
            try {
                Os.kill(mShellPid, OsConstants.SIGKILL);
            } catch (ErrnoException e) {
                Logger.logWarn(mClient, LOG_TAG, "Failed sending SIGKILL: " + e.getMessage());
            }
        }
    }

    /**
     * Discard this session and release its native terminal after the subprocess
     * has stopped. Safe to call more than once.
     */
    public void close() {
        boolean killProcess;
        synchronized (this) {
            if (mCloseRequested) return;
            mCloseRequested = true;
            killProcess = mShellPid > 0;
        }
        if (killProcess)
            finishIfRunning();
        else
            closeTerminalIfRequested();
    }

    private void closeTerminalIfRequested() {
        GhosttyTerminal terminal;
        synchronized (this) {
            if (!mCloseRequested || mClosed || mShellPid > 0) return;
            mClosed = true;
            terminal = mTerminal;
            mTerminal = null;
        }
        if (terminal != null) terminal.close();
    }

    /** Cleanup resources when the process exits. */
    void cleanupResources(int exitStatus) {
        int terminalFileDescriptor;
        synchronized (this) {
            mShellPid = -1;
            mShellExitStatus = exitStatus;
            terminalFileDescriptor = mTerminalFileDescriptor;
            mTerminalFileDescriptor = -1;
        }

        // Stop the reader and writer threads, and close the I/O streams
        mTerminalToProcessIOQueue.close();
        mProcessToTerminalIOQueue.close();
        if (terminalFileDescriptor >= 0) JNI.close(terminalFileDescriptor);
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    @Override
    public void workingDirectoryChanged(String workingDirectory) {
        mClient.onWorkingDirectoryChanged(this, workingDirectory);
    }

    @Override
    public void onMouseShapeChanged(int shape) {
        mClient.onMouseShapeChanged(this, shape);
    }

    @Override
    public void onDesktopNotification(String title, String body) {
        mClient.onDesktopNotification(this, title, body);
    }

    @Override
    public void onProgressReport(int state, int progress) {
        mClient.onProgressReport(this, state, progress);
    }

    public synchronized boolean isRunning() {
        return mShellPid != -1;
    }

    /** Only valid if not {@link #isRunning()}. */
    public synchronized int getExitStatus() {
        return mShellExitStatus;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    @Deprecated
    public int onOscClipboard(int location, String mimeType, byte[] data, boolean clear) {
        return mClient.onOscClipboard(this, location, mimeType, data, clear);
    }

    @Override
    public int onOscClipboard(int location, String[] mimeTypes, byte[][] data, boolean clear) {
        return mClient.onOscClipboard(this, location, mimeTypes, data, clear);
    }

    @Override
    public int onOscClipboardReadPermission(String name, boolean granted,
                                            boolean canRemember) {
        GhosttyTerminal terminal = mTerminal;
        if (terminal == null) return 0;
        terminal.beginClipboardPrompt();
        return mClient.onOscClipboardReadPermission(
            this, name, granted, canRemember);
    }

    @Override
    public String[] onOscClipboardMimeTypes(int location) {
        return mClient.onOscClipboardMimeTypes(this, location);
    }

    @Override
    @Deprecated
    public byte[] onOscClipboardRead(int location) {
        return mClient.onOscClipboardRead(this, location);
    }

    @Override
    public byte[] onOscClipboardRead(int location, String mimeType) {
        return mClient.onOscClipboardRead(this, location, mimeType);
    }

    @Override
    public void onOscClipboardReadComplete() {
        mClient.onOscClipboardReadComplete(this);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

    public int getPid() {
        return mShellPid;
    }

    /** Returns the shell's working directory or null if it was unavailable. */
    public String getCwd() {
        GhosttyTerminal terminal = mTerminal;
        if (terminal != null) {
            String reportedCwd = normalizeReportedWorkingDirectory(
                terminal.getWorkingDirectory());
            if (reportedCwd != null) return reportedCwd;
        }
        if (mShellPid < 1) {
            return null;
        }
        try {
            final String cwdSymlink = String.format("/proc/%s/cwd/", mShellPid);
            String outputPath = new File(cwdSymlink).getCanonicalPath();
            String outputPathWithTrailingSlash = outputPath;
            if (!outputPath.endsWith("/")) {
                outputPathWithTrailingSlash += '/';
            }
            if (!cwdSymlink.equals(outputPathWithTrailingSlash)) {
                return outputPath;
            }
        } catch (IOException | SecurityException e) {
            Logger.logStackTraceWithMessage(mClient, LOG_TAG, "Error getting current directory", e);
        }
        return null;
    }

    static String normalizeReportedWorkingDirectory(String value) {
        if (value == null || value.isEmpty()) return null;
        if (value.startsWith("/")) return value;
        try {
            java.net.URI uri = new java.net.URI(value);
            String scheme = uri.getScheme();
            if (scheme == null || !("file".equalsIgnoreCase(scheme) ||
                "kitty-shell-cwd".equalsIgnoreCase(scheme))) {
                return null;
            }
            String host = uri.getHost();
            if (host != null && !host.isEmpty() &&
                !"localhost".equalsIgnoreCase(host)) return null;
            String path = uri.getPath();
            return path == null || !path.startsWith("/") ? null : path;
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }

    private static FileDescriptor wrapFileDescriptor(int fileDescriptor, TerminalSessionClient client) {
        FileDescriptor result = new FileDescriptor();
        try {
            Field descriptorField;
            try {
                descriptorField = FileDescriptor.class.getDeclaredField("descriptor");
            } catch (NoSuchFieldException e) {
                // For desktop java:
                descriptorField = FileDescriptor.class.getDeclaredField("fd");
            }
            descriptorField.setAccessible(true);
            descriptorField.set(result, fileDescriptor);
        } catch (NoSuchFieldException | IllegalAccessException | IllegalArgumentException e) {
            Logger.logStackTraceWithMessage(client, LOG_TAG, "Error accessing FileDescriptor#descriptor private field", e);
            System.exit(1);
        }
        return result;
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        final byte[] mReceiveBuffer = new byte[PROCESS_INPUT_BUFFER_SIZE];

        @Override
        public void handleMessage(Message msg) {
            GhosttyTerminal terminal = mTerminal;
            if (terminal != null && terminal.isClipboardPromptActive()) {
                if (msg.what == MSG_PROCESS_EXITED)
                    mDeferredProcessExitStatus = (Integer) msg.obj;
                return;
            }
            int bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false);
            if (bytesRead > 0) {
                mTerminal.feed(mReceiveBuffer, 0, bytesRead);
                notifyScreenUpdate();
            }

            Integer deferredExit = mDeferredProcessExitStatus;
            mDeferredProcessExitStatus = null;
            if (deferredExit != null) {
                Message message = obtainMessage(MSG_PROCESS_EXITED, deferredExit);
                message.sendToTarget();
            }

            mProcessInputPending.set(false);
            if (mProcessToTerminalIOQueue.hasReadableBytes() &&
                mProcessInputPending.compareAndSet(false, true)) {
                sendEmptyMessage(MSG_NEW_INPUT);
            }

            if (msg.what == MSG_PROCESS_EXITED) {
                int exitCode = (Integer) msg.obj;
                cleanupResources(exitCode);

                String exitDescription = "\r\n[Process completed";
                if (exitCode > 0) {
                    // Non-zero process exit.
                    exitDescription += " (code " + exitCode + ")";
                } else if (exitCode < 0) {
                    // Negated signal.
                    exitDescription += " (signal " + (-exitCode) + ")";
                }
                exitDescription += " - press Enter]";

                byte[] bytesToWrite = exitDescription.getBytes(StandardCharsets.UTF_8);
                mTerminal.feed(bytesToWrite, 0, bytesToWrite.length);
                notifyScreenUpdate();

                mClient.onSessionFinished(TerminalSession.this);
                closeTerminalIfRequested();
            }
        }

    }

}
