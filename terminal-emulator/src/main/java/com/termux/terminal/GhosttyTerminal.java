package com.termux.terminal;

import androidx.annotation.Nullable;

import android.view.Surface;

import java.nio.charset.StandardCharsets;

/**
 * Java ownership wrapper for one libghostty-vt terminal.
 *
 * All methods serialize access to the native terminal. Renderers use the same
 * native handle and participate in the native lock.
 */
public final class GhosttyTerminal implements AutoCloseable {

    static {
        System.loadLibrary("termux");
    }

    public static final int CURSOR_STYLE_BAR = 0;
    public static final int CURSOR_STYLE_BLOCK = 1;
    public static final int CURSOR_STYLE_UNDERLINE = 2;
    public static final int CURSOR_STYLE_BLOCK_HOLLOW = 3;

    public static final int KEY_ACTION_RELEASE = 0;
    public static final int KEY_ACTION_PRESS = 1;
    public static final int KEY_ACTION_REPEAT = 2;

    public static final int MOD_SHIFT = 1 << 0;
    public static final int MOD_CTRL = 1 << 1;
    public static final int MOD_ALT = 1 << 2;
    public static final int MOD_SUPER = 1 << 3;
    public static final int MOD_CAPS_LOCK = 1 << 4;
    public static final int MOD_NUM_LOCK = 1 << 5;
    public static final int MOD_SHIFT_SIDE = 1 << 6;
    public static final int MOD_CTRL_SIDE = 1 << 7;
    public static final int MOD_ALT_SIDE = 1 << 8;
    public static final int MOD_SUPER_SIDE = 1 << 9;

    public static final int MOUSE_ACTION_PRESS = 0;
    public static final int MOUSE_ACTION_RELEASE = 1;
    public static final int MOUSE_ACTION_MOTION = 2;

    public static final int MOUSE_BUTTON_NONE = 0;
    public static final int MOUSE_BUTTON_LEFT = 1;
    public static final int MOUSE_BUTTON_RIGHT = 2;
    public static final int MOUSE_BUTTON_MIDDLE = 3;
    public static final int MOUSE_BUTTON_FOUR = 4;
    public static final int MOUSE_BUTTON_FIVE = 5;

    public static final int MOUSE_SHAPE_DEFAULT = 0;
    public static final int MOUSE_SHAPE_CONTEXT_MENU = 1;
    public static final int MOUSE_SHAPE_HELP = 2;
    public static final int MOUSE_SHAPE_POINTER = 3;
    public static final int MOUSE_SHAPE_PROGRESS = 4;
    public static final int MOUSE_SHAPE_WAIT = 5;
    public static final int MOUSE_SHAPE_CELL = 6;
    public static final int MOUSE_SHAPE_CROSSHAIR = 7;
    public static final int MOUSE_SHAPE_TEXT = 8;
    public static final int MOUSE_SHAPE_VERTICAL_TEXT = 9;
    public static final int MOUSE_SHAPE_ALIAS = 10;
    public static final int MOUSE_SHAPE_COPY = 11;
    public static final int MOUSE_SHAPE_MOVE = 12;
    public static final int MOUSE_SHAPE_NO_DROP = 13;
    public static final int MOUSE_SHAPE_NOT_ALLOWED = 14;
    public static final int MOUSE_SHAPE_GRAB = 15;
    public static final int MOUSE_SHAPE_GRABBING = 16;
    public static final int MOUSE_SHAPE_ALL_SCROLL = 17;
    public static final int MOUSE_SHAPE_COL_RESIZE = 18;
    public static final int MOUSE_SHAPE_ROW_RESIZE = 19;
    public static final int MOUSE_SHAPE_N_RESIZE = 20;
    public static final int MOUSE_SHAPE_E_RESIZE = 21;
    public static final int MOUSE_SHAPE_S_RESIZE = 22;
    public static final int MOUSE_SHAPE_W_RESIZE = 23;
    public static final int MOUSE_SHAPE_NE_RESIZE = 24;
    public static final int MOUSE_SHAPE_NW_RESIZE = 25;
    public static final int MOUSE_SHAPE_SE_RESIZE = 26;
    public static final int MOUSE_SHAPE_SW_RESIZE = 27;
    public static final int MOUSE_SHAPE_EW_RESIZE = 28;
    public static final int MOUSE_SHAPE_NS_RESIZE = 29;
    public static final int MOUSE_SHAPE_NESW_RESIZE = 30;
    public static final int MOUSE_SHAPE_NWSE_RESIZE = 31;
    public static final int MOUSE_SHAPE_ZOOM_IN = 32;
    public static final int MOUSE_SHAPE_ZOOM_OUT = 33;

    private long mNativeHandle;
    private final Object mRendererLock = new Object();
    private boolean mClipboardPromptActive;
    private boolean mFeedActive;
    private boolean mClosing;
    private boolean mClosePending;
    private int[] mPendingSize;

    public static int[] measureFont(int textSize, @Nullable String fontPath) {
        return nativeMeasureFont(textSize, fontPath);
    }

    public GhosttyTerminal(TerminalOutput output, int columns, int rows,
                           int cellWidthPixels, int cellHeightPixels,
                           int transcriptRows) {
        mNativeHandle = nativeCreate(output, columns, rows, cellWidthPixels,
            cellHeightPixels, transcriptRows);
        if (mNativeHandle == 0) {
            throw new IllegalStateException("Could not create libghostty terminal");
        }
    }

    public synchronized long getNativeHandle() {
        if (mClipboardPromptActive || mClosing)
            throw new IllegalStateException("libghostty terminal is not available");
        return requireHandle();
    }

    public void feed(byte[] data, int offset, int count) {
        long handle;
        synchronized (this) {
            if (mClosing || count <= 0) return;
            if (mFeedActive)
                throw new IllegalStateException("Concurrent terminal feeds are not supported");
            handle = getHandleIfOpen();
            if (handle == 0) return;
            mFeedActive = true;
        }
        try {
            nativeFeed(handle, data, offset, count);
        } finally {
            finishFeed();
        }
    }

    public synchronized void resize(int columns, int rows, int cellWidthPixels,
                                    int cellHeightPixels) {
        if (mClipboardPromptActive) {
            mPendingSize = new int[]{columns, rows, cellWidthPixels, cellHeightPixels};
            return;
        }
        long handle = getHandleIfOpen();
        if (handle != 0) {
            nativeResize(handle, columns, rows, cellWidthPixels,
                cellHeightPixels);
        }
    }

    public synchronized void reset() {
        long handle = getHandleIfOpen();
        if (handle != 0) nativeReset(handle);
    }

    public synchronized int getColumns() {
        long handle = getHandleIfOpen();
        return handle == 0 ? 0 : nativeGetInt(handle, 1);
    }

    public synchronized int getRows() {
        long handle = getHandleIfOpen();
        return handle == 0 ? 0 : nativeGetInt(handle, 2);
    }

    public synchronized int getScrollbackRows() {
        long handle = getHandleIfOpen();
        return handle == 0 ? 0 : nativeGetInt(handle, 15);
    }

    public synchronized int getTotalRows() {
        long handle = getHandleIfOpen();
        return handle == 0 ? 0 : nativeGetInt(handle, 14);
    }

    public synchronized int getViewportOffset() {
        long handle = getHandleIfOpen();
        return handle == 0 ? 0 : nativeGetViewportOffset(handle);
    }

    public synchronized boolean isAlternateBufferActive() {
        long handle = getHandleIfOpen();
        return handle != 0 && nativeGetInt(handle, 6) == 1;
    }

    public synchronized boolean isMouseTrackingActive() {
        long handle = getHandleIfOpen();
        return handle != 0 && nativeGetBoolean(handle, 11);
    }

    public synchronized int getMouseShape() {
        long handle = getHandleIfOpen();
        return handle == 0 ? MOUSE_SHAPE_TEXT : nativeGetInt(handle, 34);
    }

    public synchronized boolean isCursorVisible() {
        long handle = getHandleIfOpen();
        return handle != 0 && nativeGetBoolean(handle, 7);
    }

    public synchronized boolean isViewportActive() {
        long handle = getHandleIfOpen();
        return handle != 0 && nativeGetBoolean(handle, 32);
    }

    public synchronized int getBackgroundColor() {
        long handle = getHandleIfOpen();
        return handle == 0 ? 0 : nativeGetBackgroundColor(handle);
    }

    @Nullable
    public synchronized String getTitle() {
        long handle = getHandleIfOpen();
        return handle == 0 ? null : nativeGetString(handle, 12);
    }

    @Nullable
    public synchronized String getWorkingDirectory() {
        long handle = getHandleIfOpen();
        return handle == 0 ? null : nativeGetString(handle, 13);
    }

    public synchronized String getTranscript(boolean unwrap, boolean trim) {
        long handle = getHandleIfOpen();
        byte[] data = handle == 0
            ? null : nativeFormatTranscript(handle, unwrap, trim);
        return data == null ? "" : new String(data, StandardCharsets.UTF_8);
    }

    public synchronized String getViewportText() {
        long handle = getHandleIfOpen();
        byte[] data = handle == 0 ? null : nativeFormatViewport(handle);
        return data == null ? "" : new String(data, StandardCharsets.UTF_8);
    }

    public synchronized void setColorScheme(int[] colors) {
        long handle = getHandleIfOpen();
        if (handle != 0) nativeSetColorScheme(handle, colors);
    }

    public synchronized void setDefaultCursor(int style, boolean blink) {
        long handle = getHandleIfOpen();
        if (handle != 0) nativeSetDefaultCursor(handle, style, blink);
    }

    public synchronized void setGlyphProtocolEnabled(boolean enabled) {
        long handle = getHandleIfOpen();
        if (handle != 0) nativeSetGlyphProtocolEnabled(handle, enabled);
    }

    public synchronized void setKittyGraphicsOptions(long storageLimit,
                                                      @Nullable String tempDirectory) {
        long handle = getHandleIfOpen();
        if (handle != 0) {
            nativeSetKittyGraphicsOptions(handle, storageLimit, tempDirectory);
        }
    }

    public synchronized void paste(String text) {
        long handle = getHandleIfOpen();
        if (handle != 0 && text != null && !text.isEmpty()) {
            nativePaste(handle, text);
        }
    }

    public synchronized boolean sendKey(int key, int action, int modifiers,
                                        @Nullable String text,
                                        int unshiftedCodePoint) {
        long handle = getHandleIfOpen();
        return handle != 0 && nativeSendKey(handle, key, action, modifiers, text,
            unshiftedCodePoint);
    }

    public synchronized boolean sendMouse(int action, int button, int modifiers,
                                          float x, float y, int screenWidth,
                                          int screenHeight, int cellWidth,
                                          int cellHeight) {
        long handle = getHandleIfOpen();
        return handle != 0 && nativeSendMouse(handle, action, button, modifiers,
            x, y, screenWidth, screenHeight, cellWidth, cellHeight);
    }

    public synchronized void sendFocus(boolean focused) {
        long handle = getHandleIfOpen();
        if (handle != 0) nativeSendFocus(handle, focused);
    }

    public synchronized void scrollViewport(int rows) {
        long handle = getHandleIfOpen();
        if (handle != 0) nativeScrollViewport(handle, rows);
    }

    public synchronized void scrollToBottom() {
        long handle = getHandleIfOpen();
        if (handle != 0) nativeScrollToBottom(handle);
    }

    public void attachSurface(Surface surface, int width, int height,
                              int textSize, @Nullable String fontPath) {
        waitForClipboardPrompt();
        synchronized (mRendererLock) {
            waitForClipboardPrompt();
            long handle = getHandleIfOpen();
            if (handle == 0) return;
            nativeAttachSurface(handle, surface, width, height,
                textSize, fontPath);
        }
    }

    public void resizeSurface(int width, int height, int textSize,
                              @Nullable String fontPath) {
        waitForClipboardPrompt();
        synchronized (mRendererLock) {
            waitForClipboardPrompt();
            long handle = getHandleIfOpen();
            if (handle == 0) return;
            nativeResizeSurface(handle, width, height, textSize,
                fontPath);
        }
    }

    public boolean render(boolean cursorVisible) {
        waitForClipboardPrompt();
        synchronized (mRendererLock) {
            waitForClipboardPrompt();
            long handle = getHandleIfOpen();
            return handle != 0 && nativeRender(handle, cursorVisible);
        }
    }

    public void detachSurface() {
        waitForClipboardPrompt();
        synchronized (mRendererLock) {
            waitForClipboardPrompt();
            long handle = getHandleIfOpen();
            if (handle != 0) nativeDetachSurface(handle);
        }
    }

    @Nullable
    public synchronized int[] selectWordOrOutput(int column, int viewportRow) {
        long handle = getHandleIfOpen();
        return handle == 0
            ? null : nativeSelectWordOrOutput(handle, column, viewportRow);
    }

    @Nullable
    public synchronized String getWordAt(int column, int viewportRow) {
        long handle = getHandleIfOpen();
        byte[] data = handle == 0
            ? null : nativeGetWordAt(handle, column, viewportRow);
        return data == null ? null : new String(data, StandardCharsets.UTF_8);
    }

    public synchronized boolean setSelection(int startColumn, int startViewportRow,
                                             int endColumn, int endViewportRow) {
        long handle = getHandleIfOpen();
        return handle != 0 && nativeSetSelection(handle, startColumn,
            startViewportRow, endColumn, endViewportRow);
    }

    public synchronized void clearSelection() {
        long handle = getHandleIfOpen();
        if (handle != 0) nativeClearSelection(handle);
    }

    @Nullable
    public synchronized String getSelectedText() {
        long handle = getHandleIfOpen();
        byte[] data = handle == 0 ? null : nativeGetSelectedText(handle);
        return data == null ? null : new String(data, StandardCharsets.UTF_8);
    }

    @Nullable
    public synchronized String getHyperlink(int column, int viewportRow) {
        long handle = getHandleIfOpen();
        byte[] data = handle == 0
            ? null : nativeGetHyperlink(handle, column, viewportRow);
        return data == null ? null : new String(data, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        synchronized (this) {
            if (mNativeHandle == 0 || mClosing) return;
            mClosing = true;
            if (mFeedActive || mClipboardPromptActive) {
                mClosePending = true;
                return;
            }
        }
        synchronized (mRendererLock) {
            synchronized (this) {
                if (mClipboardPromptActive) {
                    mClosePending = true;
                    return;
                }
                if (mNativeHandle != 0) {
                    nativeDestroy(mNativeHandle);
                    mNativeHandle = 0;
                }
            }
        }
    }

    synchronized void beginClipboardPrompt() {
        mClipboardPromptActive = true;
    }

    private void finishFeed() {
        int[] size;
        boolean close;
        synchronized (this) {
            mFeedActive = false;
            mClipboardPromptActive = false;
            notifyAll();
            close = mClosePending;
            mClosePending = false;
            size = close ? null : mPendingSize;
            mPendingSize = null;
        }
        if (close) destroyAfterFeed();
        else if (size != null) resize(size[0], size[1], size[2], size[3]);
    }

    synchronized boolean isClipboardPromptActive() {
        return mClipboardPromptActive;
    }

    private void waitForClipboardPrompt() {
        boolean interrupted = false;
        synchronized (this) {
            while (mClipboardPromptActive) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }

    private long requireHandle() {
        if (mNativeHandle == 0) {
            throw new IllegalStateException("libghostty terminal is closed");
        }
        return mNativeHandle;
    }

    private synchronized long getHandleIfOpen() {
        return mClipboardPromptActive || mClosing ? 0 : mNativeHandle;
    }

    private void destroyAfterFeed() {
        synchronized (mRendererLock) {
            synchronized (this) {
                if (mNativeHandle != 0) {
                    nativeDestroy(mNativeHandle);
                    mNativeHandle = 0;
                }
            }
        }
    }

    private static native long nativeCreate(TerminalOutput output, int columns,
                                            int rows, int cellWidthPixels,
                                            int cellHeightPixels,
                                            int transcriptRows);
    private static native int[] nativeMeasureFont(int textSize, String fontPath);
    private static native void nativeDestroy(long handle);
    private static native void nativeFeed(long handle, byte[] data, int offset,
                                          int count);
    private static native void nativeResize(long handle, int columns, int rows,
                                            int cellWidthPixels,
                                            int cellHeightPixels);
    private static native void nativeReset(long handle);
    private static native int nativeGetInt(long handle, int data);
    private static native boolean nativeGetBoolean(long handle, int data);
    private static native int nativeGetBackgroundColor(long handle);
    private static native String nativeGetString(long handle, int data);
    private static native int nativeGetViewportOffset(long handle);
    private static native byte[] nativeFormatTranscript(long handle,
                                                        boolean unwrap,
                                                        boolean trim);
    private static native byte[] nativeFormatViewport(long handle);
    private static native void nativeSetColorScheme(long handle, int[] colors);
    private static native void nativeSetDefaultCursor(long handle, int style,
                                                      boolean blink);
    private static native void nativeSetGlyphProtocolEnabled(long handle,
                                                             boolean enabled);
    private static native void nativeSetKittyGraphicsOptions(long handle,
                                                             long storageLimit,
                                                             String tempDirectory);
    private static native void nativePaste(long handle, String text);
    private static native boolean nativeSendKey(long handle, int key, int action,
                                                int modifiers, String text,
                                                int unshiftedCodePoint);
    private static native boolean nativeSendMouse(long handle, int action,
                                                  int button, int modifiers,
                                                  float x, float y,
                                                  int screenWidth,
                                                  int screenHeight,
                                                  int cellWidth,
                                                  int cellHeight);
    private static native void nativeSendFocus(long handle, boolean focused);
    private static native void nativeScrollViewport(long handle, int rows);
    private static native void nativeScrollToBottom(long handle);
    private static native void nativeAttachSurface(long handle, Surface surface,
                                                   int width, int height,
                                                   int textSize,
                                                   String fontPath);
    private static native void nativeResizeSurface(long handle, int width,
                                                   int height, int textSize,
                                                   String fontPath);
    private static native boolean nativeRender(long handle,
                                               boolean cursorVisible);
    private static native void nativeDetachSurface(long handle);
    private static native int[] nativeSelectWordOrOutput(long handle,
                                                         int column,
                                                         int viewportRow);
    private static native byte[] nativeGetWordAt(long handle, int column,
                                                 int viewportRow);
    private static native boolean nativeSetSelection(long handle,
                                                     int startColumn,
                                                     int startViewportRow,
                                                     int endColumn,
                                                     int endViewportRow);
    private static native void nativeClearSelection(long handle);
    private static native byte[] nativeGetSelectedText(long handle);
    private static native byte[] nativeGetHyperlink(long handle, int column,
                                                    int viewportRow);
}
