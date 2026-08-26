package com.termux.app.terminal;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.net.Uri;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.ListView;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.shared.interact.ShareUtils;
import com.termux.shared.notification.NotificationUtils;
import com.termux.shared.termux.notification.TermuxNotificationUtils;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.interact.TextInputDialogUtils;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.shared.termux.TermuxConstants;
import com.termux.app.TermuxService;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.terminal.io.BellHandler;
import com.termux.shared.logger.Logger;
import com.termux.terminal.GhosttyTerminal;
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** The {@link TerminalSessionClient} implementation that may require an {@link Activity} for its interface methods. */
public class TermuxTerminalSessionActivityClient extends TermuxTerminalSessionClientBase {

    private final TermuxActivity mActivity;

    private static final int MAX_SESSIONS = 8;

    static final int MAX_OSC_CLIPBOARD_BYTES = 64 * 1024 * 1024;
    private static final int MAX_OSC_CLIPBOARD_MIME_TYPES = 16;
    private static final int MAX_OSC_CLIPBOARD_MIME_LENGTH = 256;
    private static final int MAX_OSC_CLIPBOARD_URI_ITEMS = 1024;
    private static final int CLIPBOARD_PERMISSION_DENIED = 0;
    private static final int CLIPBOARD_PERMISSION_ONCE = 1;
    private static final int CLIPBOARD_PERMISSION_ALWAYS = 2;
    private static final int MAX_OSC_NOTIFICATION_CHARS = 4096;
    private static final long OSC_NOTIFICATION_MIN_INTERVAL_MS = 1000;
    private static final long OSC_NOTIFICATION_DUPLICATE_INTERVAL_MS = 5000;
    private static final long OSC_PROGRESS_TIMEOUT_MS = 15000;
    private static final int OSC_PROGRESS_ERROR_COLOR = 0xffd32f2f;
    private static final int OSC_PROGRESS_NORMAL_COLOR = 0xff2196f3;
    private static final String OSC_NOTIFICATION_CHANNEL_ID =
        "termux_osc_notification_channel";
    private static final String OSC_NOTIFICATION_CHANNEL_NAME =
        "Terminal notifications";
    private long mLastOscNotificationTime;
    private String mLastOscNotificationTitle;
    private String mLastOscNotificationBody;
    private boolean mClipboardPermissionPromptShowing;
    private AlertDialog mClipboardPermissionDialog;
    private int[] mClipboardPermissionResult;
    private TerminalSession mClipboardReadSession;
    private ClipData mClipboardReadClip;
    private Set<String> mClipboardReadMimeTypes;
    private final android.os.Handler mMainHandler =
        new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable mHideProgressRunnable;

    private SoundPool mBellSoundPool;

    private int mBellSoundId;

    private static final String LOG_TAG = "TermuxTerminalSessionActivityClient";

    public TermuxTerminalSessionActivityClient(TermuxActivity activity) {
        this.mActivity = activity;
        this.mHideProgressRunnable = () -> {
            ProgressBar progress = mActivity.findViewById(R.id.terminal_progress_bar);
            if (progress != null) progress.setVisibility(android.view.View.GONE);
        };
    }

    /**
     * Should be called when mActivity.onCreate() is called
     */
    public void onCreate() {
        // Set terminal fonts and colors
        checkForFontAndColors();
    }

    /**
     * Should be called when mActivity.onStart() is called
     */
    public void onStart() {
        // The service has connected, but data may have changed since we were last in the foreground.
        // Get the session stored in shared preferences stored by {@link #onStop} if its valid,
        // otherwise get the last session currently running.
        if (mActivity.getTermuxService() != null) {
            setCurrentSession(getCurrentStoredSessionOrLast());
            termuxSessionListNotifyUpdated();
        }

        // The current terminal session may have changed while being away, force
        // a refresh of the displayed terminal.
        mActivity.getTerminalView().onScreenUpdated();
    }

    /**
     * Should be called when mActivity.onResume() is called
     */
    public void onResume() {
        // Just initialize the mBellSoundPool and load the sound, otherwise bell might not run
        // the first time bell key is pressed and play() is called, since sound may not be loaded
        // quickly enough before the call to play(). https://stackoverflow.com/questions/35435625
        loadBellSoundPool();
    }

    /**
     * Should be called when mActivity.onStop() is called
     */
    public void onStop() {
        denyPendingClipboardPermission();
        // Store current session in shared preferences so that it can be restored later in
        // {@link #onStart} if needed.
        setCurrentStoredSession();
        mMainHandler.removeCallbacks(mHideProgressRunnable);
        mHideProgressRunnable.run();

        // Release mBellSoundPool resources, specially to prevent exceptions like the following to be thrown
        // java.util.concurrent.TimeoutException: android.media.SoundPool.finalize() timed out after 10 seconds
        // Bell is not played in background anyways
        // Related: https://stackoverflow.com/a/28708351/14686958
        releaseBellSoundPool();
    }

    /**
     * Should be called when mActivity.reloadActivityStyling() is called
     */
    public void onReloadActivityStyling() {
        // Set terminal fonts and colors
        checkForFontAndColors();
    }



    @Override
    public void onTextChanged(@NonNull TerminalSession changedSession) {
        if (!mActivity.isVisible()) return;

        if (mActivity.getCurrentSession() == changedSession) mActivity.getTerminalView().onScreenUpdated();
    }

    @Override
    public void onTitleChanged(@NonNull TerminalSession updatedSession) {
        if (!mActivity.isVisible()) return;

        if (updatedSession != mActivity.getCurrentSession()) {
            // Only show toast for other sessions than the current one, since the user
            // probably consciously caused the title change to change in the current session
            // and don't want an annoying toast for that.
            mActivity.showToast(toToastTitle(updatedSession), true);
        }

        termuxSessionListNotifyUpdated();
    }

    @Override
    public void onWorkingDirectoryChanged(@NonNull TerminalSession session,
                                          @Nullable String workingDirectory) {
        // The value is consumed lazily by TerminalSession.getCwd().
    }

    @Override
    public void onMouseShapeChanged(@NonNull TerminalSession session, int shape) {
        if (mActivity.isVisible() && mActivity.getCurrentSession() == session)
            mActivity.getTerminalView().setMouseShape(shape);
    }

    @Override
    public void onDesktopNotification(@NonNull TerminalSession session,
                                      @Nullable String title, @Nullable String body) {
        if (!mActivity.isVisible() || mActivity.getCurrentSession() != session) return;
        title = sanitizeOscNotificationText(title);
        body = sanitizeOscNotificationText(body);
        if (TextUtils.isEmpty(title)) title = TextUtils.isEmpty(session.getTitle())
            ? TermuxConstants.TERMUX_APP_NAME : session.getTitle();
        if (TextUtils.isEmpty(body)) return;

        long now = android.os.SystemClock.elapsedRealtime();
        if (now - mLastOscNotificationTime < OSC_NOTIFICATION_MIN_INTERVAL_MS ||
            (TextUtils.equals(title, mLastOscNotificationTitle) &&
             TextUtils.equals(body, mLastOscNotificationBody) &&
             now - mLastOscNotificationTime < OSC_NOTIFICATION_DUPLICATE_INTERVAL_MS)) {
            return;
        }
        mLastOscNotificationTime = now;
        mLastOscNotificationTitle = title;
        mLastOscNotificationBody = body;

        NotificationUtils.setupNotificationChannel(
            mActivity, OSC_NOTIFICATION_CHANNEL_ID, OSC_NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT);
        PendingIntent intent = PendingIntent.getActivity(
            mActivity, 0, TermuxActivity.newInstance(mActivity),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = NotificationUtils.geNotificationBuilder(
            mActivity, OSC_NOTIFICATION_CHANNEL_ID, Notification.PRIORITY_DEFAULT,
            title, body, body, intent, null, NotificationUtils.NOTIFICATION_MODE_SILENT);
        NotificationManager manager = NotificationUtils.getNotificationManager(mActivity);
        if (builder != null && manager != null) {
            builder.setSmallIcon(R.drawable.ic_service_notification)
                .setAutoCancel(true).setShowWhen(true);
            manager.notify(TermuxNotificationUtils.getNextNotificationId(mActivity),
                           builder.build());
        }
    }

    @Override
    public void onProgressReport(@NonNull TerminalSession session, int state, int value) {
        if (!mActivity.isVisible() || mActivity.getCurrentSession() != session) return;
        ProgressBar progress = mActivity.findViewById(R.id.terminal_progress_bar);
        if (progress == null) return;
        mMainHandler.removeCallbacks(mHideProgressRunnable);
        if (state == 0) {
            progress.setVisibility(android.view.View.GONE);
            return;
        }
        progress.setVisibility(android.view.View.VISIBLE);
        ColorStateList progressColor = ColorStateList.valueOf(
            state == 2 ? OSC_PROGRESS_ERROR_COLOR : OSC_PROGRESS_NORMAL_COLOR);
        progress.setProgressTintList(progressColor);
        progress.setIndeterminateTintList(progressColor);
        if (state == 3 || ((state == 1 || state == 2) && value < 0)) {
            progress.setIndeterminate(true);
        } else {
            progress.setIndeterminate(false);
            if (value >= 0) progress.setProgress(Math.max(0, Math.min(100, value)));
        }
        progress.setContentDescription(mActivity.getString(
            R.string.terminal_progress_description, state, value));
        mMainHandler.postDelayed(mHideProgressRunnable, OSC_PROGRESS_TIMEOUT_MS);
    }

    static String sanitizeOscNotificationText(@Nullable String value) {
        if (value == null) return null;
        StringBuilder clean = new StringBuilder(
            Math.min(value.length(), MAX_OSC_NOTIFICATION_CHARS));
        for (int i = 0; i < value.length();) {
            int codePoint = value.codePointAt(i);
            int charCount = Character.charCount(codePoint);
            i += charCount;
            if (clean.length() + charCount > MAX_OSC_NOTIFICATION_CHARS) break;
            if (codePoint == '\n' || codePoint == '\t' ||
                (codePoint >= 0x20 && (codePoint < 0x7f || codePoint > 0x9f))) {
                clean.appendCodePoint(codePoint);
            }
        }
        return clean.toString();
    }

    @Override
    public void onSessionFinished(@NonNull TerminalSession finishedSession) {
        TermuxService service = mActivity.getTermuxService();

        if (service == null || service.wantsToStop()) {
            // The service wants to stop as soon as possible.
            mActivity.finishActivityIfNotFinishing();
            return;
        }

        int index = service.getIndexOfSession(finishedSession);

        // For plugin commands that expect the result back, we should immediately close the session
        // and send the result back instead of waiting fo the user to press enter.
        // The plugin can handle/show errors itself.
        boolean isPluginExecutionCommandWithPendingResult = false;
        TermuxSession termuxSession = service.getTermuxSession(index);
        if (termuxSession != null) {
            isPluginExecutionCommandWithPendingResult = termuxSession.getExecutionCommand().isPluginExecutionCommandWithPendingResult();
            if (isPluginExecutionCommandWithPendingResult)
                Logger.logVerbose(LOG_TAG, "The \"" + finishedSession.mSessionName + "\" session will be force finished automatically since result in pending.");
        }

        if (mActivity.isVisible() && finishedSession != mActivity.getCurrentSession()) {
            // Show toast for non-current sessions that exit.
            // Verify that session was not removed before we got told about it finishing:
            if (index >= 0)
                mActivity.showToast(toToastTitle(finishedSession) + " - exited", true);
        }

        if (mActivity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            // On Android TV devices we need to use older behaviour because we may
            // not be able to have multiple launcher icons.
            if (service.getTermuxSessionsSize() > 1 || isPluginExecutionCommandWithPendingResult) {
                removeFinishedSession(finishedSession);
            }
        } else {
            // Once we have a separate launcher icon for the failsafe session, it
            // should be safe to auto-close session on exit code '0' or '130'.
            if (finishedSession.getExitStatus() == 0 || finishedSession.getExitStatus() == 130 || isPluginExecutionCommandWithPendingResult) {
                removeFinishedSession(finishedSession);
            }
        }
    }

    @Override
    public void onCopyTextToClipboard(@NonNull TerminalSession session, String text) {
        if (!mActivity.isVisible()) return;

        ShareUtils.copyTextToClipboard(mActivity, text);
    }

    @Override
    @Deprecated
    public int onOscClipboard(@NonNull TerminalSession session, int location,
                              String mimeType, byte[] data, boolean clear) {
        return onOscClipboard(session, location,
            clear ? new String[0] : new String[]{mimeType},
            clear ? new byte[0][] : new byte[][]{data}, clear);
    }

    @Override
    public int onOscClipboard(@NonNull TerminalSession session, int location,
                              String[] mimeTypes, byte[][] data, boolean clear) {
        if (!mActivity.isVisible() || mActivity.getCurrentSession() != session)
            return TerminalOutput.OSC_CLIPBOARD_RESULT_DENIED;
        if (location != 0) return TerminalOutput.OSC_CLIPBOARD_RESULT_UNSUPPORTED;
        ClipboardManager clipboard = (ClipboardManager)
            mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return TerminalOutput.OSC_CLIPBOARD_RESULT_UNSUPPORTED;

        try {
            if (clear) {
                clipboard.clearPrimaryClip();
                return TerminalOutput.OSC_CLIPBOARD_RESULT_SUCCESS;
            }

            if (mimeTypes == null || data == null || mimeTypes.length != data.length)
                return TerminalOutput.OSC_CLIPBOARD_RESULT_INVALID_DATA;

            String plainText = null;
            String htmlText = null;
            String uriList = null;
            String intentUri = null;
            String fallbackText = null;
            int totalBytes = 0;
            Map<String, String> textRepresentations = new LinkedHashMap<>();
            for (int i = 0; i < mimeTypes.length; i++) {
                if (mimeTypes[i] == null || data[i] == null ||
                    data[i].length > MAX_OSC_CLIPBOARD_BYTES - totalBytes)
                    return TerminalOutput.OSC_CLIPBOARD_RESULT_INVALID_DATA;
                if (mimeTypes[i].length() > MAX_OSC_CLIPBOARD_MIME_LENGTH)
                    return TerminalOutput.OSC_CLIPBOARD_RESULT_INVALID_DATA;
                totalBytes += data[i].length;
                String normalized = normalizeMimeType(mimeTypes[i]);
                if (!isTextMimeType(normalized))
                    return TerminalOutput.OSC_CLIPBOARD_RESULT_UNSUPPORTED;
                if (!isPlainTextMimeType(normalized) && !isConcreteMimeType(normalized))
                    return TerminalOutput.OSC_CLIPBOARD_RESULT_UNSUPPORTED;
                String value = decodeClipboardText(data[i]);
                if (value == null) return TerminalOutput.OSC_CLIPBOARD_RESULT_INVALID_DATA;
                String previous = textRepresentations.put(normalized, value);
                if (previous != null && !previous.equals(value))
                    return TerminalOutput.OSC_CLIPBOARD_RESULT_UNSUPPORTED;
                if (isPlainTextMimeType(normalized)) {
                    if (plainText != null && !plainText.equals(value))
                        return TerminalOutput.OSC_CLIPBOARD_RESULT_UNSUPPORTED;
                    plainText = value;
                }
                else if (ClipDescription.MIMETYPE_TEXT_HTML.equals(normalized)) htmlText = value;
                else if (ClipDescription.MIMETYPE_TEXT_URILIST.equals(normalized)) uriList = value;
                else if (ClipDescription.MIMETYPE_TEXT_INTENT.equals(normalized)) intentUri = value;
                else if (fallbackText == null) fallbackText = value;
            }

            String text = plainText != null ? plainText : fallbackText;
            if (text == null && htmlText != null)
                text = android.text.Html.fromHtml(htmlText).toString();
            for (Map.Entry<String, String> representation : textRepresentations.entrySet()) {
                String type = representation.getKey();
                if (!isStandardAndroidTextMimeType(type) &&
                    (text == null || !text.equals(representation.getValue())))
                    return TerminalOutput.OSC_CLIPBOARD_RESULT_UNSUPPORTED;
            }

            List<Uri> uris = uriList == null ? Collections.emptyList() : parseUriList(uriList);
            if (uris == null) return TerminalOutput.OSC_CLIPBOARD_RESULT_UNSUPPORTED;
            if (uriList != null && uris.isEmpty())
                return TerminalOutput.OSC_CLIPBOARD_RESULT_INVALID_DATA;
            Intent intent = intentUri == null ? null
                : Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME);
            Uri firstUri = uris.isEmpty() ? null : uris.get(0);
            if (text == null && htmlText == null && intent == null && firstUri == null)
                return TerminalOutput.OSC_CLIPBOARD_RESULT_UNSUPPORTED;

            Set<String> representedTypes = new LinkedHashSet<>();
            for (String type : textRepresentations.keySet()) {
                if (!isPlainTextMimeType(type)) representedTypes.add(type);
            }
            if (text != null) representedTypes.add(ClipDescription.MIMETYPE_TEXT_PLAIN);
            if (firstUri != null) {
                representedTypes.add(ClipDescription.MIMETYPE_TEXT_URILIST);
            }
            if (representedTypes.size() > MAX_OSC_CLIPBOARD_MIME_TYPES)
                return TerminalOutput.OSC_CLIPBOARD_RESULT_UNSUPPORTED;
            if (firstUri != null) {
                try {
                    String uriType = mActivity.getContentResolver().getType(firstUri);
                    addClipboardMimeType(representedTypes, uriType);
                } catch (RuntimeException ignored) {
                    // The URI list remains valid without optional provider metadata.
                }
            }
            ClipData clip = new ClipData("OSC clipboard",
                representedTypes.toArray(new String[0]),
                new ClipData.Item(text, htmlText, intent, firstUri));
            for (int i = 1; i < uris.size(); i++)
                clip.addItem(new ClipData.Item(uris.get(i)));
            clipboard.setPrimaryClip(clip);
            return TerminalOutput.OSC_CLIPBOARD_RESULT_SUCCESS;
        } catch (URISyntaxException e) {
            return TerminalOutput.OSC_CLIPBOARD_RESULT_INVALID_DATA;
        } catch (SecurityException e) {
            return TerminalOutput.OSC_CLIPBOARD_RESULT_DENIED;
        } catch (RuntimeException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to update OSC clipboard", e);
            return TerminalOutput.OSC_CLIPBOARD_RESULT_IO_ERROR;
        }
    }

    @Override
    public int onOscClipboardReadPermission(@NonNull TerminalSession session,
                                            String name, boolean granted,
                                            boolean canRemember) {
        if (!mActivity.isVisible() || mActivity.getCurrentSession() != session)
            return CLIPBOARD_PERMISSION_DENIED;
        if (granted) return CLIPBOARD_PERMISSION_ONCE;

        return showClipboardPermissionDialog(canRemember);
    }

    @Override
    public String[] onOscClipboardMimeTypes(@NonNull TerminalSession session, int location) {
        if (!isCurrentClipboardSession(session, location)) return null;
        mClipboardReadSession = null;
        mClipboardReadClip = null;
        mClipboardReadMimeTypes = null;
        ClipboardManager clipboard = (ClipboardManager)
            mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return null;
        try {
            ClipData clip = clipboard.getPrimaryClip();
            ClipDescription description = clip == null ? null : clip.getDescription();
            if (clip == null || description == null) return new String[0];
            if (description.getMimeTypeCount() > MAX_OSC_CLIPBOARD_MIME_TYPES ||
                clip.getItemCount() > MAX_OSC_CLIPBOARD_URI_ITEMS) return null;
            Set<String> types = new LinkedHashSet<>();
            boolean hasText = false;
            for (int i = 0; i < clip.getItemCount(); i++) {
                if (clip.getItemAt(i).getText() != null) {
                    hasText = true;
                    break;
                }
            }
            for (int i = 0; i < clip.getItemCount(); i++) {
                ClipData.Item item = clip.getItemAt(i);
                if (item.getText() != null)
                    if (!addClipboardMimeType(types, ClipDescription.MIMETYPE_TEXT_PLAIN)) return null;
                if (item.getHtmlText() != null)
                    if (!addClipboardMimeType(types, ClipDescription.MIMETYPE_TEXT_HTML) ||
                        !addClipboardMimeType(types, ClipDescription.MIMETYPE_TEXT_PLAIN)) return null;
                if (item.getUri() != null) {
                    if (!addClipboardMimeType(types, ClipDescription.MIMETYPE_TEXT_URILIST) ||
                        !addClipboardMimeType(types, ClipDescription.MIMETYPE_TEXT_PLAIN)) return null;
                }
                if (item.getIntent() != null) {
                    if (!addClipboardMimeType(types, ClipDescription.MIMETYPE_TEXT_INTENT) ||
                        !addClipboardMimeType(types, ClipDescription.MIMETYPE_TEXT_PLAIN)) return null;
                }
            }
            if (hasText) {
                for (int i = 0; i < description.getMimeTypeCount(); i++) {
                    String type = normalizeMimeType(description.getMimeType(i));
                    if (isTextMimeType(type) && !isStandardAndroidTextMimeType(type))
                        addClipboardMimeType(types, type);
                }
            }
            ContentResolver resolver = mActivity.getContentResolver();
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri == null) continue;
                try {
                    addClipboardMimeType(types, resolver.getType(uri));
                } catch (RuntimeException ignored) {
                }
                try {
                    String[] streamTypes = resolver.getStreamTypes(uri, "*/*");
                    if (streamTypes == null ||
                        streamTypes.length > MAX_OSC_CLIPBOARD_MIME_TYPES) continue;
                    for (String streamType : streamTypes)
                        addClipboardMimeType(types, streamType);
                } catch (RuntimeException ignored) {
                    // Inline representations remain available if the provider is unavailable.
                }
            }
            mClipboardReadSession = session;
            mClipboardReadClip = clip;
            mClipboardReadMimeTypes = types;
            return types.toArray(new String[0]);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    @Deprecated
    public byte[] onOscClipboardRead(@NonNull TerminalSession session, int location) {
        if (onOscClipboardMimeTypes(session, location) == null) return null;
        try {
            return onOscClipboardRead(session, location, ClipDescription.MIMETYPE_TEXT_PLAIN);
        } finally {
            onOscClipboardReadComplete(session);
        }
    }

    @Override
    public byte[] onOscClipboardRead(@NonNull TerminalSession session, int location,
                                     String mimeType) {
        if (!isCurrentClipboardSession(session, location) || mimeType == null) return null;
        try {
            ClipData clip = mClipboardReadSession == session ? mClipboardReadClip : null;
            Set<String> availableTypes = mClipboardReadSession == session
                ? mClipboardReadMimeTypes : null;
            if (clip == null || availableTypes == null || clip.getItemCount() == 0) return null;
            String normalized = normalizeMimeType(mimeType);
            String advertisedType = isPlainTextMimeType(normalized)
                ? ClipDescription.MIMETYPE_TEXT_PLAIN : normalized;
            if (!availableTypes.contains(advertisedType)) return null;
            ContentResolver resolver = mActivity.getContentResolver();
            Exception uriReadFailure = null;
            if (ClipDescription.MIMETYPE_TEXT_URILIST.equals(normalized)) {
                if (clip.getItemCount() > MAX_OSC_CLIPBOARD_URI_ITEMS) return null;
                StringBuilder uris = new StringBuilder();
                for (int i = 0; i < clip.getItemCount() &&
                        i < MAX_OSC_CLIPBOARD_URI_ITEMS; i++) {
                    Uri uri = clip.getItemAt(i).getUri();
                    if (uri == null) continue;
                    if (uris.length() > 0) uris.append('\n');
                    uris.append(uri);
                    if (uris.length() > MAX_OSC_CLIPBOARD_BYTES) return null;
                }
                return uris.length() == 0 ? null : encodeClipboardText(uris);
            }
            for (int i = 0; i < clip.getItemCount() &&
                    i < MAX_OSC_CLIPBOARD_URI_ITEMS; i++) {
                ClipData.Item item = clip.getItemAt(i);
                if (ClipDescription.MIMETYPE_TEXT_HTML.equals(normalized) &&
                    item.getHtmlText() != null)
                    return encodeClipboardText(item.getHtmlText());
                if (ClipDescription.MIMETYPE_TEXT_INTENT.equals(normalized) &&
                    item.getIntent() != null)
                    return encodeClipboardText(
                        item.getIntent().toUri(Intent.URI_INTENT_SCHEME));
                Uri uri = item.getUri();
                String uriMimeType = null;
                try {
                    if (uri != null) uriMimeType = resolver.getType(uri);
                } catch (RuntimeException e) {
                    uriReadFailure = e;
                }
                if (uri != null && uriMimeType != null &&
                    ClipDescription.compareMimeTypes(
                        normalizeMimeType(uriMimeType), normalized)) {
                    try (InputStream input = resolver.openInputStream(uri)) {
                        if (input != null) return readClipboardBytes(input);
                    } catch (IOException e) {
                        uriReadFailure = e;
                    } catch (RuntimeException e) {
                        uriReadFailure = e;
                    }
                }
                if (uri != null) {
                    String[] streamTypes;
                    try {
                        streamTypes = resolver.getStreamTypes(uri, normalized);
                    } catch (RuntimeException e) {
                        uriReadFailure = e;
                        continue;
                    }
                    if (streamTypes == null) continue;
                    if (streamTypes.length > MAX_OSC_CLIPBOARD_MIME_TYPES) continue;
                    for (String streamType : streamTypes) {
                        if (!ClipDescription.compareMimeTypes(
                                normalizeMimeType(streamType), normalized)) continue;
                        try (AssetFileDescriptor descriptor =
                                 resolver.openTypedAssetFileDescriptor(uri, streamType, null)) {
                            if (descriptor == null) continue;
                            try (InputStream input = descriptor.createInputStream()) {
                                return readClipboardBytes(input);
                            }
                        } catch (IOException e) {
                            uriReadFailure = e;
                        } catch (RuntimeException e) {
                            uriReadFailure = e;
                        }
                    }
                }
            }
            for (int i = 0; i < clip.getItemCount() &&
                    i < MAX_OSC_CLIPBOARD_URI_ITEMS; i++) {
                ClipData.Item item = clip.getItemAt(i);
                if (isPlainTextMimeType(normalized)) {
                    CharSequence text = item.getText();
                    if (text != null) return encodeClipboardText(text);
                    if (item.getHtmlText() != null)
                        return encodeClipboardText(android.text.Html.fromHtml(item.getHtmlText()));
                    Uri textUri = item.getUri();
                    String textUriType = null;
                    try {
                        if (textUri != null) textUriType = resolver.getType(textUri);
                    } catch (RuntimeException e) {
                        uriReadFailure = e;
                    }
                    if (textUri != null && textUriType != null &&
                        ClipDescription.compareMimeTypes(
                            normalizeMimeType(textUriType), "text/*")) {
                        try (InputStream input = resolver.openInputStream(textUri)) {
                            if (input != null) return readClipboardBytes(input);
                        } catch (IOException e) {
                            uriReadFailure = e;
                        } catch (RuntimeException e) {
                            uriReadFailure = e;
                        }
                    }
                    if (textUri != null) return encodeClipboardText(textUri.toString());
                    Intent intent = item.getIntent();
                    if (intent != null)
                        return encodeClipboardText(intent.toUri(Intent.URI_INTENT_SCHEME));
                }
                if (isTextMimeType(normalized) &&
                    !isStandardAndroidTextMimeType(normalized) && item.getText() != null)
                    return encodeClipboardText(item.getText());
            }
            if (uriReadFailure != null)
                Logger.logStackTraceWithMessage(
                    LOG_TAG, "Failed to read an OSC clipboard URI", uriReadFailure);
            return null;
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read OSC clipboard", e);
            return null;
        } catch (SecurityException e) {
            return null;
        } catch (RuntimeException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read OSC clipboard", e);
            return null;
        }
    }

    @Override
    public void onOscClipboardReadComplete(@NonNull TerminalSession session) {
        if (mClipboardReadSession == session) {
            mClipboardReadSession = null;
            mClipboardReadClip = null;
            mClipboardReadMimeTypes = null;
        }
    }

    private boolean isCurrentClipboardSession(TerminalSession session, int location) {
        return mActivity.isVisible() && mActivity.getCurrentSession() == session && location == 0;
    }

    @Nullable
    private static List<Uri> parseUriList(String uriList) {
        List<Uri> uris = new ArrayList<>();
        int start = 0;
        while (start <= uriList.length()) {
            int end = uriList.indexOf('\n', start);
            if (end < 0) end = uriList.length();
            int contentStart = start;
            int contentEnd = end;
            while (contentStart < contentEnd && uriList.charAt(contentStart) <= ' ')
                contentStart++;
            while (contentEnd > contentStart && uriList.charAt(contentEnd - 1) <= ' ')
                contentEnd--;
            if (contentStart < contentEnd && uriList.charAt(contentStart) != '#') {
                if (uris.size() >= MAX_OSC_CLIPBOARD_URI_ITEMS) return null;
                uris.add(Uri.parse(uriList.substring(contentStart, contentEnd)));
            }
            if (end == uriList.length()) break;
            start = end + 1;
        }
        return uris;
    }

    @Nullable
    private static String decodeClipboardText(byte[] data) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data)).toString();
            return text.indexOf('\0') < 0 ? text : null;
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private int showClipboardPermissionDialog(boolean canRemember) {
        if (Looper.myLooper() != Looper.getMainLooper() ||
            mClipboardPermissionPromptShowing) return CLIPBOARD_PERMISSION_DENIED;
        final int[] result = {CLIPBOARD_PERMISSION_DENIED};
        AlertDialog.Builder builder = new AlertDialog.Builder(mActivity)
            .setTitle(R.string.title_clipboard_read_permission)
            .setMessage(R.string.msg_clipboard_read_permission)
            .setPositiveButton(R.string.action_clipboard_allow_once, null)
            .setNegativeButton(R.string.action_clipboard_deny, null);
        if (canRemember)
            builder.setNeutralButton(R.string.action_clipboard_always_allow, null);
        AlertDialog dialog = builder.create();
        dialog.show();
        TypedArray dialogColors = dialog.getContext().obtainStyledAttributes(
            new int[]{android.R.attr.textColorPrimary});
        ColorStateList buttonTextColor;
        try {
            buttonTextColor = dialogColors.getColorStateList(0);
        } finally {
            dialogColors.recycle();
        }
        if (buttonTextColor != null) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(buttonTextColor);
            if (canRemember)
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(buttonTextColor);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(buttonTextColor);
        }
        mClipboardPermissionPromptShowing = true;
        mClipboardPermissionDialog = dialog;
        mClipboardPermissionResult = result;
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(
            view -> finishClipboardPermission(dialog, result, CLIPBOARD_PERMISSION_ONCE));
        if (canRemember)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(
                view -> finishClipboardPermission(dialog, result, CLIPBOARD_PERMISSION_ALWAYS));
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(
            view -> finishClipboardPermission(dialog, result, CLIPBOARD_PERMISSION_DENIED));
        dialog.setOnCancelListener(
            ignored -> finishClipboardPermission(dialog, result, CLIPBOARD_PERMISSION_DENIED));
        try {
            Looper.loop();
        } catch (ClipboardPermissionResolved expected) {
            // The clipboard callback is synchronous, so pump the main looper until the user responds.
        } finally {
            mClipboardPermissionPromptShowing = false;
            mClipboardPermissionDialog = null;
            mClipboardPermissionResult = null;
        }
        return result[0];
    }

    private void denyPendingClipboardPermission() {
        AlertDialog dialog = mClipboardPermissionDialog;
        int[] result = mClipboardPermissionResult;
        if (dialog == null || result == null) return;
        mMainHandler.post(() -> {
            if (mClipboardPermissionDialog == dialog)
                finishClipboardPermission(dialog, result, CLIPBOARD_PERMISSION_DENIED);
        });
    }

    private void finishClipboardPermission(AlertDialog dialog, int[] result, int value) {
        if (mClipboardPermissionDialog != dialog) return;
        result[0] = value;
        dialog.dismiss();
        throw new ClipboardPermissionResolved();
    }

    private static final class ClipboardPermissionResolved extends RuntimeException {
        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    static byte[] readClipboardBytes(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (true) {
            int read = input.read(buffer);
            if (read == -1) break;
            if (read == 0) {
                int value = input.read();
                if (value == -1) break;
                if (output.size() == MAX_OSC_CLIPBOARD_BYTES)
                    throw new IOException("OSC clipboard content is too large");
                output.write(value);
                continue;
            }
            if (read > MAX_OSC_CLIPBOARD_BYTES - output.size())
                throw new IOException("OSC clipboard content is too large");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    static byte[] encodeClipboardText(CharSequence text) throws IOException {
        if (text.length() > MAX_OSC_CLIPBOARD_BYTES)
            throw new IOException("OSC clipboard content is too large");
        byte[] data = text.toString().getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_OSC_CLIPBOARD_BYTES)
            throw new IOException("OSC clipboard content is too large");
        return data;
    }

    private static boolean isPlainTextMimeType(String mimeType) {
        return ClipDescription.MIMETYPE_TEXT_PLAIN.equals(mimeType) ||
            "utf8_string".equals(mimeType) || "text".equals(mimeType) ||
            "string".equals(mimeType);
    }

    private static boolean isTextMimeType(String mimeType) {
        return mimeType.startsWith("text/") || isPlainTextMimeType(mimeType);
    }

    private static boolean isStandardAndroidTextMimeType(String mimeType) {
        return isPlainTextMimeType(mimeType) ||
            ClipDescription.MIMETYPE_TEXT_HTML.equals(mimeType) ||
            ClipDescription.MIMETYPE_TEXT_URILIST.equals(mimeType) ||
            ClipDescription.MIMETYPE_TEXT_INTENT.equals(mimeType);
    }

    private static boolean addClipboardMimeType(Set<String> types, String mimeType) {
        if (mimeType == null) return true;
        if (mimeType.length() > MAX_OSC_CLIPBOARD_MIME_LENGTH) return false;
        for (int i = 0; i < mimeType.length(); i++) {
            if (Character.isWhitespace(mimeType.charAt(i))) return false;
        }
        String normalized = normalizeMimeType(mimeType);
        if (!isConcreteMimeType(normalized))
            return false;
        if (types.contains(normalized)) return true;
        if (types.size() >= MAX_OSC_CLIPBOARD_MIME_TYPES) return false;
        types.add(normalized);
        return true;
    }

    private static boolean isConcreteMimeType(String mimeType) {
        int slash = mimeType.indexOf('/');
        if (slash <= 0 || slash != mimeType.lastIndexOf('/') ||
            slash == mimeType.length() - 1) return false;
        for (int i = 0; i < mimeType.length(); i++) {
            if (i == slash) continue;
            char character = mimeType.charAt(i);
            if (character < 0x21 || character > 0x7e || character == '*' ||
                "()<>@,;:\\\"/[]?=".indexOf(character) >= 0) return false;
        }
        return true;
    }

    private static String normalizeMimeType(String mimeType) {
        if (mimeType == null) return "";
        String normalized = mimeType.toLowerCase(Locale.ROOT).replaceAll("\\s", "");
        int parameters = normalized.indexOf(';');
        return parameters < 0 ? normalized : normalized.substring(0, parameters);
    }

    @Override
    public void onPasteTextFromClipboard(@Nullable TerminalSession session) {
        if (!mActivity.isVisible()) return;

        String text = ShareUtils.getTextStringFromClipboardIfSet(mActivity, true);
        if (text != null)
            mActivity.getTerminalView().paste(text);
    }

    @Override
    public void onBell(@NonNull TerminalSession session) {
        if (!mActivity.isVisible()) return;

        switch (mActivity.getProperties().getBellBehaviour()) {
            case TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_VIBRATE:
                BellHandler.getInstance(mActivity).doBell();
                break;
            case TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_BEEP:
                loadBellSoundPool();
                if (mBellSoundPool != null)
                    mBellSoundPool.play(mBellSoundId, 1.f, 1.f, 1, 0, 1.f);
                break;
            case TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_IGNORE:
                // Ignore the bell character.
                break;
        }
    }

    @Override
    public void onColorsChanged(@NonNull TerminalSession changedSession) {
        if (mActivity.getCurrentSession() == changedSession)
            updateBackgroundColor();
    }

    @Override
    public void onTerminalCursorStateChange(boolean enabled) {
        // Do not start cursor blinking thread if activity is not visible
        if (enabled && !mActivity.isVisible()) {
            Logger.logVerbose(LOG_TAG, "Ignoring call to start cursor blinking since activity is not visible");
            return;
        }

        // If cursor is to enabled now, then start cursor blinking if blinking is enabled
        // otherwise stop cursor blinking
        mActivity.getTerminalView().setTerminalCursorBlinkerState(enabled, false);
    }

    @Override
    public void setTerminalShellPid(@NonNull TerminalSession terminalSession, int pid) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;
        
        TermuxSession termuxSession = service.getTermuxSessionForTerminalSession(terminalSession);
        if (termuxSession != null)
            termuxSession.getExecutionCommand().mPid = pid;
    }


    /**
     * Should be called when mActivity.onResetTerminalSession() is called
     */
    public void onResetTerminalSession() {
        // Ensure blinker starts again after reset if cursor blinking was disabled before reset like
        // with "tput civis" which would have called onTerminalCursorStateChange()
        mActivity.getTerminalView().setTerminalCursorBlinkerState(true, true);
    }



    @Override
    public Integer getTerminalCursorStyle() {
        return mActivity.getProperties().getTerminalCursorStyle();
    }



    /** Load mBellSoundPool */
    private synchronized void loadBellSoundPool() {
        if (mBellSoundPool == null) {
            mBellSoundPool = new SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
                new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build()).build();

            try {
                mBellSoundId = mBellSoundPool.load(mActivity, R.raw.bell, 1);
            } catch (Exception e){
                // Catch java.lang.RuntimeException: Unable to resume activity {com.termux/com.termux.app.TermuxActivity}: android.content.res.Resources$NotFoundException: File res/raw/bell.ogg from drawable resource ID
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load bell sound pool", e);
            }
        }
    }

    /** Release mBellSoundPool resources */
    private synchronized void releaseBellSoundPool() {
        if (mBellSoundPool != null) {
            mBellSoundPool.release();
            mBellSoundPool = null;
        }
    }



    /** Try switching to session. */
    public void setCurrentSession(TerminalSession session) {
        if (session == null) return;
        if (mActivity.getCurrentSession() != session && mClipboardPermissionDialog != null) {
            denyPendingClipboardPermission();
            mMainHandler.post(() -> setCurrentSession(session));
            return;
        }

        if (mActivity.getTerminalView().attachSession(session)) {
            mMainHandler.removeCallbacks(mHideProgressRunnable);
            mHideProgressRunnable.run();
            // notify about switched session if not already displaying the session
            notifyOfSessionChange();
        }

        // We call the following even when the session is already being displayed since config may
        // be stale, like current session not selected or scrolled to.
        checkAndScrollToSession(session);
        updateBackgroundColor();
    }

    void notifyOfSessionChange() {
        if (!mActivity.isVisible()) return;

        if (!mActivity.getProperties().areTerminalSessionChangeToastsDisabled()) {
            TerminalSession session = mActivity.getCurrentSession();
            mActivity.showToast(toToastTitle(session), false);
        }
    }

    public void switchToSession(boolean forward) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        TerminalSession currentTerminalSession = mActivity.getCurrentSession();
        int index = service.getIndexOfSession(currentTerminalSession);
        int size = service.getTermuxSessionsSize();
        if (forward) {
            if (++index >= size) index = 0;
        } else {
            if (--index < 0) index = size - 1;
        }

        TermuxSession termuxSession = service.getTermuxSession(index);
        if (termuxSession != null)
            setCurrentSession(termuxSession.getTerminalSession());
    }

    public void switchToSession(int index) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        TermuxSession termuxSession = service.getTermuxSession(index);
        if (termuxSession != null)
            setCurrentSession(termuxSession.getTerminalSession());
    }

    @SuppressLint("InflateParams")
    public void renameSession(final TerminalSession sessionToRename) {
        if (sessionToRename == null) return;

        TextInputDialogUtils.textInput(mActivity, R.string.title_rename_session, sessionToRename.mSessionName, R.string.action_rename_session_confirm, text -> {
            renameSession(sessionToRename, text);
            termuxSessionListNotifyUpdated();
        }, -1, null, -1, null, null);
    }

    private void renameSession(TerminalSession sessionToRename, String text) {
        if (sessionToRename == null) return;
        sessionToRename.mSessionName = text;
        TermuxService service = mActivity.getTermuxService();
        if (service != null) {
            TermuxSession termuxSession = service.getTermuxSessionForTerminalSession(sessionToRename);
            if (termuxSession != null)
                termuxSession.getExecutionCommand().shellName = text;
        }
    }

    public void addNewSession(boolean isFailSafe, String sessionName) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        if (service.getTermuxSessionsSize() >= MAX_SESSIONS) {
            new AlertDialog.Builder(mActivity).setTitle(R.string.title_max_terminals_reached).setMessage(R.string.msg_max_terminals_reached)
                .setPositiveButton(android.R.string.ok, null).show();
        } else {
            TerminalSession currentSession = mActivity.getCurrentSession();

            String workingDirectory;
            if (currentSession == null) {
                workingDirectory = mActivity.getProperties().getDefaultWorkingDirectory();
            } else {
                workingDirectory = currentSession.getCwd();
            }

            TermuxSession newTermuxSession = service.createTermuxSession(null, null, null, workingDirectory, isFailSafe, sessionName);
            if (newTermuxSession == null) return;

            TerminalSession newTerminalSession = newTermuxSession.getTerminalSession();
            setCurrentSession(newTerminalSession);

            mActivity.getDrawer().closeDrawers();
        }
    }

    public void setCurrentStoredSession() {
        TerminalSession currentSession = mActivity.getCurrentSession();
        if (currentSession != null)
            mActivity.getPreferences().setCurrentSession(currentSession.mHandle);
        else
            mActivity.getPreferences().setCurrentSession(null);
    }

    /** The current session as stored or the last one if that does not exist. */
    public TerminalSession getCurrentStoredSessionOrLast() {
        TerminalSession stored = getCurrentStoredSession();

        if (stored != null) {
            // If a stored session is in the list of currently running sessions, then return it
            return stored;
        } else {
            // Else return the last session currently running
            TermuxService service = mActivity.getTermuxService();
            if (service == null) return null;

            TermuxSession termuxSession = service.getLastTermuxSession();
            if (termuxSession != null)
                return termuxSession.getTerminalSession();
            else
                return null;
        }
    }

    private TerminalSession getCurrentStoredSession() {
        String sessionHandle = mActivity.getPreferences().getCurrentSession();

        // If no session is stored in shared preferences
        if (sessionHandle == null)
            return null;

        // Check if the session handle found matches one of the currently running sessions
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return null;

        return service.getTerminalSessionForHandle(sessionHandle);
    }

    public void removeFinishedSession(TerminalSession finishedSession) {
        // Return pressed with finished session - remove it.
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        int index = service.removeTermuxSession(finishedSession);

        int size = service.getTermuxSessionsSize();
        if (size == 0) {
            // There are no sessions to show, so finish the activity.
            mActivity.finishActivityIfNotFinishing();
        } else {
            if (index >= size) {
                index = size - 1;
            }
            TermuxSession termuxSession = service.getTermuxSession(index);
            if (termuxSession != null)
                setCurrentSession(termuxSession.getTerminalSession());
        }
    }

    public void termuxSessionListNotifyUpdated() {
        mActivity.termuxSessionListNotifyUpdated();
    }

    public void checkAndScrollToSession(TerminalSession session) {
        if (!mActivity.isVisible()) return;
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        final int indexOfSession = service.getIndexOfSession(session);
        if (indexOfSession < 0) return;
        final ListView termuxSessionsListView = mActivity.findViewById(R.id.terminal_sessions_list);
        if (termuxSessionsListView == null) return;

        termuxSessionsListView.setItemChecked(indexOfSession, true);
        // Delay is necessary otherwise sometimes scroll to newly added session does not happen
        termuxSessionsListView.postDelayed(() -> termuxSessionsListView.smoothScrollToPosition(indexOfSession), 1000);
    }


    String toToastTitle(TerminalSession session) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return null;

        final int indexOfSession = service.getIndexOfSession(session);
        if (indexOfSession < 0) return null;
        StringBuilder toastTitle = new StringBuilder("[" + (indexOfSession + 1) + "]");
        if (!TextUtils.isEmpty(session.mSessionName)) {
            toastTitle.append(" ").append(session.mSessionName);
        }
        String title = session.getTitle();
        if (!TextUtils.isEmpty(title)) {
            // Space to "[${NR}] or newline after session name:
            toastTitle.append(session.mSessionName == null ? " " : "\n");
            toastTitle.append(title);
        }
        return toastTitle.toString();
    }


    public void checkForFontAndColors() {
        try {
            File colorsFile = TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE;
            File fontFile = TermuxConstants.TERMUX_FONT_FILE;

            final Properties props = new Properties();
            if (colorsFile.isFile()) {
                try (InputStream in = new FileInputStream(colorsFile)) {
                    props.load(in);
                }
            }

            TerminalColors.COLOR_SCHEME.updateWith(props);
            TerminalSession session = mActivity.getCurrentSession();
            if (session != null && session.getTerminal() != null) {
                session.getTerminal().setColorScheme(TerminalColors.COLOR_SCHEME.copyColors());
            }
            updateBackgroundColor();

            mActivity.getTerminalView().setFontFile(
                fontFile.exists() && fontFile.length() > 0 ? fontFile : null);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error in checkForFontAndColors()", e);
        }
    }

    public void updateBackgroundColor() {
        if (!mActivity.isVisible()) return;
        TerminalSession session = mActivity.getCurrentSession();
        if (session != null && session.getTerminal() != null) {
            mActivity.getWindow().getDecorView().setBackgroundColor(
                session.getTerminal().getBackgroundColor());
        }
    }

}
