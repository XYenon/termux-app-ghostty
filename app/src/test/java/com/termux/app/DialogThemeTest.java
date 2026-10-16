package com.termux.app;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.text.Spanned;
import android.text.style.URLSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.CheckedTextView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.R;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.app.terminal.TermuxSessionsListViewController;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.extrakeys.ExtraKeysConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.extrakeys.SpecialButton;
import com.termux.shared.termux.interact.TextInputDialogUtils;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.GhosttyTerminal;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, qualifiers = "en-rUS-w360dp-mdpi", shadows = DialogThemeTest.SearchTerminal.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DialogThemeTest {
    @Test
    public void lightDialogActionsAreReadable() throws Exception {
        checkDialog();
    }

    @Test
    @Config(qualifiers = "en-rUS-w360dp-night-mdpi")
    public void darkDialogActionsAreReadable() throws Exception {
        checkDialog();
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-night-mdpi")
    public void searchWorksOnNarrowScreenWithLargeText() throws Exception {
        RuntimeEnvironment.setFontScale(1.3f);
        TermuxActivity activity = Robolectric.buildActivity(TermuxActivity.class).get();
        activity.setTheme(R.style.Theme_TermuxActivity_DayNight_NoActionBar);
        activity.getDelegate().installViewFactory();
        checkSearch(activity, "dark-zh-narrow");
    }

    @Test
    @Config(sdk = 33)
    public void searchWindowFitsAboveKeyboard() throws Exception {
        TermuxActivity activity = Robolectric.buildActivity(TermuxActivity.class).get();
        activity.setTheme(R.style.Theme_TermuxActivity_DayNight_NoActionBar);
        activity.getDelegate().installViewFactory();
        AlertDialog dialog = showSearch(activity, View.VISIBLE);
        WindowManager.LayoutParams attributes = new WindowManager.LayoutParams();
        attributes.copyFrom(dialog.getWindow().getAttributes());
        // Run Android's legacy soft-input-mode conversion, which Robolectric's window manager skips.
        ReflectionHelpers.callStaticMethod(Class.forName("android.view.ViewRootImpl"),
            "adjustLayoutParamsForCompatibility",
            ReflectionHelpers.ClassParameter.from(WindowManager.LayoutParams.class, attributes));
        assertEquals("Search window must fit the IME as well as the system bars",
            WindowInsets.Type.systemBars() | WindowInsets.Type.ime(), attributes.getFitInsetsTypes());
        assertEquals("Search must not leave space for the hidden toolbar", 0,
            dialog.getWindow().getAttributes().y);
        dialog.dismiss();
    }

    @Test
    public void searchTemporarilyHidesToolbar() {
        TermuxActivity activity = Robolectric.buildActivity(TermuxActivity.class).get();
        activity.setTheme(R.style.Theme_TermuxActivity_DayNight_NoActionBar);
        activity.getDelegate().installViewFactory();
        for (int visibility : new int[]{View.VISIBLE, View.GONE}) {
            for (boolean cancel : new boolean[]{false, true}) {
                AlertDialog dialog = showSearch(activity, visibility);
                View toolbar = activity.getTerminalToolbarViewPager();
                assertEquals(View.GONE, toolbar.getVisibility());
                if (cancel) {
                    dialog.cancel();
                } else {
                    dialog.findViewById(R.id.terminal_search_close).performClick();
                }
                ShadowLooper.idleMainLooper();
                assertFalse(dialog.isShowing());
                assertEquals("Closing search must restore the toolbar's previous visibility",
                    visibility, toolbar.getVisibility());
            }
        }
    }

    private void checkDialog() throws Exception {
        for (int theme : new int[]{R.style.Theme_TermuxActivity_DayNight_NoActionBar,
            R.style.Theme_TermuxApp_DayNight_NoActionBar}) {
            checkTheme(theme);
        }
    }

    private void checkTheme(int theme) throws Exception {
        TermuxActivity activity = Robolectric.buildActivity(TermuxActivity.class).get();
        activity.setTheme(theme);
        activity.getDelegate().installViewFactory();
        boolean dark = (activity.getResources().getConfiguration().uiMode
            & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        String mode = dark ? "dark" : "light";
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
            .setTitle("Clipboard access")
            .setMessage("Allow the terminal to read your clipboard?")
            .setPositiveButton("Allow once", null)
            .setNegativeButton("Deny", null)
            .setNeutralButton("Always allow", null)
            .show();
        checkColors(dialog, dark);
        capture(dialog.getWindow().getDecorView(), "dialog-" + theme + "-" + mode);
        dialog.dismiss();

        // Preferences use AppCompat's builder, so they must inherit the same palette.
        dialog = new AlertDialog.Builder(activity)
            .setTitle("Theme").setSingleChoiceItems(new String[]{"System", "Light", "Dark"}, 0, null)
            .setNegativeButton(android.R.string.cancel, null).show();
        checkColors(dialog, dark);
        TextView item = (TextView) dialog.getListView().getAdapter().getView(0, null, dialog.getListView());
        assertReadable(item.getCurrentTextColor(), surface(dialog.getContext()));
        capture(dialog.getWindow().getDecorView(), "choices-" + theme + "-" + mode);
        CheckedTextView checked = (CheckedTextView) dialog.getListView().getChildAt(0);
        checked.jumpDrawablesToCurrentState();
        assertTrue(dialog.getListView().isItemChecked(0));
        capture(dialog.getWindow().getDecorView(), "choices-" + theme + "-" + mode);
        dialog.dismiss();

        boolean[] callbacks = new boolean[2];
        MessageDialogUtils.showMessage(activity, "Report an issue",
            "Visit https://example.com or email help@example.com", "Continue",
            (d, which) -> callbacks[0] = true, "Cancel", null, d -> callbacks[1] = true);
        dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        checkColors(dialog, dark);
        TextView message = dialog.findViewById(android.R.id.message);
        assertEquals(2, ((Spanned) message.getText()).getSpans(0, message.length(), URLSpan.class).length);
        assertReadable(message.getLinkTextColors().getDefaultColor(), surface(dialog.getContext()));
        capture(dialog.getWindow().getDecorView(), "message-" + theme + "-" + mode);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertTrue(callbacks[0]);
        assertTrue(callbacks[1]);

        String[] submitted = {null};
        TextInputDialogUtils.textInput(activity, R.string.title_rename_session, "Build",
            R.string.action_rename_session_confirm, text -> submitted[0] = text,
            0, null, 0, null, null);
        dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        checkColors(dialog, dark);
        EditText input = findInput(dialog.getWindow().getDecorView());
        assertNotNull(input);
        assertEquals("Build", input.getText().toString());
        assertReadable(input.getCurrentTextColor(), surface(dialog.getContext()));
        capture(dialog.getWindow().getDecorView(), "input-" + theme + "-" + mode);
        input.setText("Release");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("Release", submitted[0]);

        if (theme == R.style.Theme_TermuxActivity_DayNight_NoActionBar) {
            TypedArray colors = activity.obtainStyledAttributes(new int[]{
                R.attr.extraKeysButtonActiveTextColor, R.attr.extraKeysButtonActiveBackgroundColor});
            assertReadable(colors.getColor(0, 0), colors.getColor(1, 0));
            colors.recycle();
            ExtraKeysView keys = new ExtraKeysView(activity, null);
            keys.reload(new ExtraKeysInfo("[['ESC','CTRL','ALT','SHIFT','TAB']]", "default",
                ExtraKeysConstants.CONTROL_CHARS_ALIASES), 60);
            keys.getSpecialButtons().get(SpecialButton.CTRL).setIsActive(true);
            FrameLayout keyRow = new FrameLayout(activity);
            keyRow.setBackgroundColor(0xff000000);
            keyRow.addView(keys, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 60));
            capture(keyRow, "extra-keys-" + mode);
            checkFailedSession(activity, mode);
            checkSearch(activity, mode);
        }
    }

    private static void checkFailedSession(TermuxActivity activity, String mode) throws Exception {
        TerminalSession session = new TerminalSession("", "", new String[0], new String[0],
            100, new TermuxTerminalSessionClientBase());
        session.mSessionName = "Build failed";
        ReflectionHelpers.setField(session, "mShellExitStatus", 1);
        TermuxSession termuxSession = ReflectionHelpers.callConstructor(TermuxSession.class,
            ReflectionHelpers.ClassParameter.from(TerminalSession.class, session),
            ReflectionHelpers.ClassParameter.from(ExecutionCommand.class, null),
            ReflectionHelpers.ClassParameter.from(TermuxSession.TermuxSessionClient.class, null),
            ReflectionHelpers.ClassParameter.from(boolean.class, false));
        View row = new TermuxSessionsListViewController(activity, Collections.singletonList(termuxSession))
            .getView(0, null, new FrameLayout(activity));
        row.setActivated(true);
        TextView title = row.findViewById(R.id.session_title);
        assertReadable(title.getCurrentTextColor(), mode.equals("dark") ? 0xff212325 : 0xffe0e0e0);
        capture(row, "failed-session-" + mode);
    }

    private static AlertDialog showSearch(TermuxActivity activity, int toolbarVisibility) {
        activity.setContentView(R.layout.activity_termux);
        activity.getTerminalToolbarViewPager().setVisibility(toolbarVisibility);
        TerminalSession session = new TerminalSession("", "", new String[0], new String[0],
            100, new TermuxTerminalSessionClientBase());
        GhosttyTerminal terminal = Shadow.newInstanceOf(GhosttyTerminal.class);
        ReflectionHelpers.setField(session, "mTerminal", terminal);
        TerminalView terminalView = new TerminalView(activity, null);
        terminalView.mTermSession = session;
        ReflectionHelpers.setField(activity, "mTerminalView", terminalView);
        new TermuxTerminalViewClient(activity, null).showTerminalSearch();
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }

    private static void checkSearch(TermuxActivity activity, String mode) throws Exception {
        AlertDialog dialog = showSearch(activity, View.VISIBLE);
        SearchTerminal search = Shadow.extract(activity.getCurrentSession().getTerminal());
        View content = dialog.getWindow().getDecorView();
        EditText input = content.findViewById(R.id.terminal_search_input);
        TextView status = content.findViewById(R.id.terminal_search_status);
        View previous = content.findViewById(R.id.terminal_search_previous);
        View next = content.findViewById(R.id.terminal_search_next);
        int background = surface(dialog.getContext());
        assertReadable(input.getCurrentTextColor(), background);
        assertReadable(input.getCurrentHintTextColor(), background);
        assertEquals(EditorInfo.IME_ACTION_SEARCH, input.getImeOptions() & EditorInfo.IME_MASK_ACTION);
        assertTrue(input.hasFocus());
        assertFalse(previous.isEnabled());
        assertFalse(next.isEnabled());
        checkSearchButtons(content, background);
        capture(content, "search-empty-" + mode);

        input.setText("error");
        assertEquals(activity.getString(R.string.terminal_search_submit), status.getText().toString());
        assertTrue(previous.isEnabled());
        assertTrue(next.isEnabled());
        capture(content, "search-pending-" + mode);
        input.onEditorAction(EditorInfo.IME_ACTION_SEARCH);
        assertEquals(activity.getString(R.string.terminal_search_results_count, 2, 7), status.getText().toString());
        assertTrue(search.highlighted);
        next.performClick();
        assertEquals(activity.getString(R.string.terminal_search_results_count, 3, 7), status.getText().toString());
        previous.performClick();
        assertEquals(activity.getString(R.string.terminal_search_results_count, 2, 7), status.getText().toString());
        assertReadable(status.getCurrentTextColor(), background);
        checkSearchButtons(content, background);
        capture(content, "search-results-" + mode);
        for (int id : new int[]{R.id.terminal_search_close, R.id.terminal_search_previous, R.id.terminal_search_next}) {
            View button = content.findViewById(id);
            assertTrue(button.getWidth() >= 48);
            assertTrue(button.getHeight() >= 48);
            assertFalse(android.text.TextUtils.isEmpty(button.getContentDescription()));
        }

        input.setText("missing");
        assertFalse(search.highlighted);
        input.onEditorAction(EditorInfo.IME_ACTION_SEARCH);
        assertEquals(activity.getString(R.string.terminal_search_no_matches), status.getText().toString());
        assertFalse(previous.isEnabled());
        assertFalse(next.isEnabled());
        capture(content, "search-no-matches-" + mode);
        input.setText("error");
        assertTrue(next.isEnabled());
        next.performClick();
        assertTrue(search.highlighted);
        input.setText("");
        assertFalse(search.highlighted);
        assertEquals("", status.getText().toString());
        assertFalse(next.isEnabled());
        input.setText("error");
        input.onEditorAction(EditorInfo.IME_ACTION_SEARCH);
        content.findViewById(R.id.terminal_search_close).performClick();
        ShadowLooper.idleMainLooper();
        assertFalse(dialog.isShowing());
        assertFalse(search.highlighted);
    }

    private static void checkSearchButtons(View view, int background) {
        if (view instanceof MaterialButton && view.isEnabled()) {
            MaterialButton button = (MaterialButton) view;
            int tint = button.getIconTint().getColorForState(button.getDrawableState(), 0);
            assertTrue("Active search icons must be readable", ColorUtils.calculateContrast(tint, background) >= 3);
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                checkSearchButtons(group.getChildAt(i), background);
        }
    }

    @Implements(GhosttyTerminal.class)
    public static class SearchTerminal {
        boolean highlighted;
        int selected = 3;
        @Implementation protected static void __staticInitializer__() {}
        @Implementation protected void clearSearch() { highlighted = false; selected = 3; }
        @Implementation protected int[] search(String query, boolean next) {
            highlighted = query.equals("error");
            selected += next ? -1 : 1;
            return highlighted ? new int[]{selected, 7} : new int[]{0, 0};
        }
    }

    private static void checkColors(AlertDialog dialog, boolean dark) {
        int background = surface(dialog.getContext());
        assertEquals("Dialog must follow the activity's night mode", dark,
            ColorUtils.calculateLuminance(background) < 0.5);
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE,
            AlertDialog.BUTTON_NEUTRAL}) {
            Button button = dialog.getButton(which);
            if (button != null && button.getVisibility() == View.VISIBLE)
                assertReadable(button.getCurrentTextColor(), background);
        }
        TextView message = dialog.findViewById(android.R.id.message);
        if (message != null && message.getVisibility() == View.VISIBLE)
            assertReadable(message.getCurrentTextColor(), background);
        TextView title = dialog.findViewById(androidx.appcompat.R.id.alertTitle);
        if (title != null && title.getVisibility() == View.VISIBLE)
            assertReadable(title.getCurrentTextColor(), background);
    }

    private static int surface(Context context) {
        return MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface,
            "DialogThemeTest");
    }

    private static void assertReadable(int foreground, int background) {
        double contrast = ColorUtils.calculateContrast(foreground, background);
        assertTrue(String.format("Text #%08x on #%08x has contrast %.2f", foreground, background, contrast),
            contrast >= 4.5);
    }

    private static EditText findInput(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText input = findInput(group.getChildAt(i));
                if (input != null) return input;
            }
        }
        return null;
    }

    private static void capture(View view, String name) throws Exception {
        ShadowLooper.idleMainLooper();
        int width = view.getResources().getConfiguration().screenWidthDp;
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.AT_MOST));
        view.layout(0, 0, width, view.getMeasuredHeight());
        ShadowLooper.idleMainLooper();
        view.forceLayout();
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.AT_MOST));
        view.layout(0, 0, width, view.getMeasuredHeight());
        Bitmap bitmap = Bitmap.createBitmap(width * 2, view.getHeight() * 2, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(2, 2);
        view.draw(canvas);
        File file = new File("build/test-screenshots/" + name + ".png");
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        try (FileOutputStream output = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        bitmap.recycle();
    }
}
