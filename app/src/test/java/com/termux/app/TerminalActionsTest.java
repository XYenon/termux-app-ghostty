package com.termux.app;

import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.termux.R;
import com.termux.app.activities.SettingsActivity;
import com.termux.app.terminal.TermuxActivityRootView;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSurfaceView;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, qualifiers = "en-rUS-w360dp-h640dp-mdpi", shadows = TerminalActionsTest.ContextTerminal.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TerminalActionsTest {
    private TermuxActivity activity;
    private TerminalView terminal;
    private ContextTerminal context;

    @Test
    public void moreKeepsCommonActionsDirectAndGroupsSessionActions() throws Exception {
        setup();
        showMenu();
        assertEquals(Arrays.asList("Find in terminal", "Previous prompt", "Next prompt",
            "Select URL", "Share", "Session"), titles());
        assertEquals(Arrays.asList("Share transcript"), titles(item(4).getSubMenu()));
        Menu session = item(5).getSubMenu();
        assertEquals(Arrays.asList("Reset", "Kill process (0)"), titles(session));
        assertTrue(session.getItem(1).isEnabled());
        assertFalse("Opening a submenu must not run Select URL",
            activity.onContextItemSelected(item(4)));
        ListView list = latestDialog().getListView();
        TextView share = (TextView) list.getAdapter().getView(4, null, list);
        assertNotNull(share.getCompoundDrawablesRelative()[2]);
        TextView find = (TextView) list.getAdapter().getView(0, share, list);
        assertNull(find.getCompoundDrawablesRelative()[2]);
        capture(latestDialog().getWindow().getDecorView(), "more-default");
        clickRow(5);
        capture(latestDialog().getWindow().getDecorView(), "more-session");
        AlertDialog dialog = latestDialog();
        dialog.findViewById(R.id.terminal_context_menu_back).performClick();
        assertEquals(Arrays.asList("Find in terminal", "Previous prompt", "Next prompt",
            "Select URL", "Share", "Session"), titles());
        assertFalse(dialog.findViewById(R.id.terminal_context_menu_back).isShown());
        clickRow(5);
        assertTrue(dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)));
        assertTrue(dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)));
        assertTrue(dialog.isShowing());
        assertEquals(6, titles().size());
        dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
        dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
        ShadowLooper.idleMainLooper();
        assertFalse(dialog.isShowing());
        ReflectionHelpers.setField(terminal.mTermSession, "mShellPid", -1);
        showMenu();
        assertFalse(item(5).getSubMenu().getItem(1).isEnabled());
        latestDialog().dismiss();
        assertTrue(terminal.showContextMenu(24, 42));
        assertEquals(6, latestDialog().getListView().getCount());
        latestDialog().dismiss();
        assertFalse(activity.onCreateOptionsMenu(null));
        assertEquals(6, latestDialog().getListView().getCount());
        latestDialog().dismiss();
        assertTrue(terminal.performLongClick());
        assertEquals(6, latestDialog().getListView().getCount());
        latestDialog().dismiss();
    }

    @Test
    public void contextualSubmenusKeepOriginalLinkAndSelection() throws Exception {
        setup();
        ReflectionHelpers.setField(terminal, "mContextHyperlink", "https://example.com/original?q=17");
        context.selectedText = "original selection";
        context.autofill = true;
        showMenu();
        assertEquals(Arrays.asList("Find in terminal", "Previous prompt", "Next prompt",
            "Links", "Share", "Session", "Autofill"), titles());
        assertEquals(Arrays.asList("Autofill username", "Autofill password"),
            titles(item(6).getSubMenu()));
        capture(latestDialog().getWindow().getDecorView(), "more-contextual");
        AlertDialog dialog = latestDialog();
        List<String> rootTitles = titles();
        String[] groups = {"links", "share", "session", "autofill"};
        for (int i = 3; i <= 6; i++) {
            clickRow(i);
            View back = dialog.findViewById(R.id.terminal_context_menu_back);
            assertTrue(back.isShown());
            assertEquals(activity.getString(R.string.action_back_to_main_menu), back.getContentDescription());
            capture(dialog.getWindow().getDecorView(), "more-contextual-" + groups[i - 3]);
            back.performClick();
            ShadowLooper.idleMainLooper();
            assertSame(dialog, latestDialog());
            assertTrue(dialog.isShowing());
            assertEquals(rootTitles, titles());
            assertEquals("https://example.com/original?q=17", terminal.getContextHyperlink());
            assertEquals("original selection", terminal.getStoredSelectedText());
        }
        clickRow(3);
        capture(latestDialog().getWindow().getDecorView(), "more-links");
        ReflectionHelpers.setField(terminal, "mContextHyperlink", "https://example.com/replaced");
        clickRow(1);
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        assertEquals("https://example.com/original?q=17",
            clipboard.getPrimaryClip().getItemAt(0).getText().toString());

        context.selectedText = "original selection";
        showMenu();
        clickRow(4);
        capture(latestDialog().getWindow().getDecorView(), "more-share");
        context.selectedText = null;
        clickRow(1);
        Intent chooser = ((ShadowActivity) Shadow.extract(activity)).getNextStartedActivity();
        Intent share = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
        assertEquals(Intent.ACTION_SEND, share.getAction());
        assertEquals("original selection", share.getStringExtra(Intent.EXTRA_TEXT));
    }

    @Test
    public void drawerActionsPersistScreenPreferenceAndOpenSettings() throws Exception {
        checkDrawer("drawer-light");
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h640dp-night-mdpi")
    public void drawerActionsFitNarrowScreenWithLargeText() throws Exception {
        RuntimeEnvironment.setFontScale(1.3f);
        checkDrawer("drawer-dark-zh-large");
    }

    private void checkDrawer(String name) throws Exception {
        setup();
        showMenu();
        capture(latestDialog().getWindow().getDecorView(), name + "-menu");
        clickRow(5);
        capture(latestDialog().getWindow().getDecorView(), name + "-submenu");
        latestDialog().dismiss();
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(activity, true);
        preferences.setKeepScreenOn(false);
        ReflectionHelpers.setField(activity, "mPreferences", preferences);
        ReflectionHelpers.callInstanceMethod(activity, "setDrawerActionsView");
        ListView sessions = activity.findViewById(R.id.terminal_sessions_list);
        sessions.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1,
            Arrays.asList("[1] Shell", "[2] Editor")));
        activity.getDrawer().openDrawer(Gravity.LEFT, false);
        layout(activity.getDrawer(), 360, 560);
        for (int id : new int[]{R.id.style_button, R.id.settings_button,
            R.id.toggle_keyboard_button, R.id.new_session_button, R.id.keep_screen_on}) {
            TextView button = activity.findViewById(id);
            assertNotNull(button);
            assertTrue(button.isShown());
            CharSequence displayedText = button.getTransformationMethod() == null ? button.getText()
                : button.getTransformationMethod().getTransformation(button.getText(), button);
            assertEquals(button.getText().toString(), displayedText.toString());
        }
        capture(activity.getDrawer(), name);
        SwitchMaterial keepScreenOn = activity.findViewById(R.id.keep_screen_on);
        keepScreenOn.performClick();
        assertTrue(keepScreenOn.isChecked());
        assertTrue(terminal.getKeepScreenOn());
        assertTrue(TermuxAppSharedPreferences.build(activity, true).shouldKeepScreenOn());
        assertTrue(activity.getDrawer().isDrawerOpen(Gravity.LEFT));
        keepScreenOn.jumpDrawablesToCurrentState();
        capture(activity.getDrawer(), name + "-screen-on");
        keepScreenOn.performClick();
        assertFalse(terminal.getKeepScreenOn());
        assertFalse(preferences.shouldKeepScreenOn());
        keepScreenOn.jumpDrawablesToCurrentState();
        activity.findViewById(R.id.settings_button).performClick();
        assertEquals(SettingsActivity.class.getName(),
            ((ShadowActivity) Shadow.extract(activity)).getNextStartedActivity().getComponent().getClassName());

        // Only the sessions scroll; the complete action group stays anchored at the bottom.
        activity.getDrawer().openDrawer(Gravity.LEFT, false);
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= 24; i++) names.add("[" + i + "] Shell");
        sessions.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, names));
        layout(activity.getDrawer(), 640, 200);
        View actions = activity.findViewById(R.id.drawer_actions);
        int actionsTop = actions.getTop();
        sessions.setSelection(sessions.getCount() - 1);
        layout(activity.getDrawer(), 640, 200);
        assertEquals(actionsTop, actions.getTop());
        for (int id : new int[]{R.id.style_button, R.id.settings_button,
            R.id.keep_screen_on, R.id.toggle_keyboard_button, R.id.new_session_button}) {
            View action = activity.findViewById(id);
            Rect visible = new Rect();
            assertTrue(action.getGlobalVisibleRect(visible));
            assertEquals("Every bottom action must stay fully visible", action.getHeight(), visible.height());
        }
        assertTrue(keepScreenOn.getBottom() <= activity.findViewById(R.id.style_button).getTop()
            + ((View) activity.findViewById(R.id.style_button).getParent()).getTop());
        capture(activity.getDrawer(), name + "-short", 640, 200);
    }

    private void setup() {
        ActivityController<TermuxActivity> controller = Robolectric.buildActivity(TermuxActivity.class);
        activity = controller.get();
        activity.setTheme(R.style.Theme_TermuxActivity_DayNight_NoActionBar);
        activity.getDelegate().installViewFactory();
        activity.setContentView(R.layout.activity_termux);
        ((TermuxActivityRootView) activity.findViewById(R.id.activity_termux_root_view)).setActivity(activity);
        terminal = activity.findViewById(R.id.terminal_view);
        context = Shadow.extract(terminal);
        terminal.mTermSession = new TerminalSession("", "", new String[0], new String[0],
            100, new TermuxTerminalSessionClientBase());
        ReflectionHelpers.setField(activity, "mTerminalView", terminal);
        ReflectionHelpers.setField(activity, "mTermuxTerminalViewClient",
            new TermuxTerminalViewClient(activity, null));
        activity.registerForContextMenu(terminal);
        controller.visible();
        layout(activity.getDrawer(), 360, 560);
    }

    private void showMenu() {
        assertTrue(terminal.showContextMenu());
        ShadowLooper.idleMainLooper();
        assertTrue(latestDialog().isShowing());
    }

    private static MenuItem item(int position) {
        return (MenuItem) latestDialog().getListView().getAdapter().getItem(position);
    }

    private static List<String> titles() {
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < latestDialog().getListView().getCount(); i++)
            titles.add(item(i).getTitle().toString());
        return titles;
    }

    private static List<String> titles(Menu menu) {
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < menu.size(); i++) titles.add(menu.getItem(i).getTitle().toString());
        return titles;
    }

    private static AlertDialog latestDialog() {
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }

    private static void clickRow(int position) {
        AlertDialog dialog = latestDialog();
        ListView list = dialog.getListView();
        list.performItemClick(list.getChildAt(position), position, list.getAdapter().getItemId(position));
        ShadowLooper.idleMainLooper();
    }

    private static void layout(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        ShadowLooper.idleMainLooper();
    }

    private static void capture(View view, String name) throws Exception {
        capture(view, name, view.getResources().getConfiguration().screenWidthDp, 560);
    }

    private static void capture(View view, String name, int width, int height) throws Exception {
        ShadowLooper.idleMainLooper();
        int heightMode = view instanceof androidx.drawerlayout.widget.DrawerLayout
            ? View.MeasureSpec.EXACTLY : View.MeasureSpec.AT_MOST;
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, heightMode));
        view.layout(0, 0, width, view.getMeasuredHeight());
        ShadowLooper.idleMainLooper();
        view.forceLayout();
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, heightMode));
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

    @Implements(TerminalView.class)
    public static class ContextTerminal extends ShadowSurfaceView {
        String selectedText;
        boolean autofill;

        @Implementation protected String getStoredSelectedText() { return selectedText; }
        @Implementation protected boolean isAutoFillEnabled() { return autofill; }
        @Implementation protected void unsetStoredSelectedText() { selectedText = null; }
        @Implementation protected void updateSize() {} // Menus do not need the native terminal renderer.
    }
}
