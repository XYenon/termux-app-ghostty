package com.termux.view;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.PopupMenu;

import com.termux.terminal.GhosttyTerminal;
import com.termux.terminal.TerminalSession;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowSurfaceView;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, shadows = TouchMouseOverlayTest.SurfaceWithoutRenderer.class)
public class TouchMouseOverlayTest {
    private final List<Integer> actions = new ArrayList<>();
    private TouchMouseDragHandler handler;
    private TouchMouseOverlay overlay;
    private ToolbarHost host;
    private TerminalView view;
    private ClipboardManager clipboard;

    @Before
    public void setUp() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        host = new ToolbarHost(activity);
        view = new TerminalView(activity, null);
        clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("", "clipboard text"));
        host.addView(view);
        activity.setContentView(host);
        handler = new TouchMouseDragHandler((event, action) -> actions.add(action));
        overlay = new TouchMouseOverlay(view, handler);
        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 40, 60, 0);
        handler.start(down, 300, 200, 8);
        down.recycle();
        overlay.update();
        assertNull(host.mode);
        MotionEvent up = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 40, 60, 0);
        handler.onTouchEvent(up);
        up.recycle();
        overlay.update();
        assertNotNull(host.mode);
        assertEquals(ActionMode.TYPE_FLOATING, host.type);
        assertEquals(4, host.mode.getMenu().size());
        assertEquals(4, host.mode.getMenu().getItem(1).getItemId());
        assertTrue(actions.isEmpty());
    }

    @Test
    public void pasteUsesClipboardAndClosesAimWithoutMouseEvents() {
        StringBuilder pasted = new StringBuilder();
        view.mTermSession = new TerminalSession("", "", new String[0], new String[0],
            100, new TermuxTerminalSessionClientBase() {
                @Override public void onPasteTextFromClipboard(TerminalSession session) {
                    assertFalse(handler.isActive());
                    assertTrue(host.mode.finished);
                    pasted.append(clipboard.getPrimaryClip().getItemAt(0).getText());
                }
            });
        assertTrue(host.mode.getMenu().findItem(4).isEnabled());
        host.choose(4);
        assertEquals("clipboard text", pasted.toString());
        assertTrue(actions.isEmpty());
        assertFalse(host.contextMenuOpened);
    }

    @Test
    public void pasteIsDisabledWhenClipboardIsEmpty() {
        clipboard.clearPrimaryClip();
        Menu menu = new PopupMenu(host.getContext(), host).getMenu();
        host.mode.callback.onCreateActionMode(host.mode, menu);
        assertFalse(menu.findItem(4).isEnabled());
    }

    @Test
    public void magnifierMovesBelowWhenThereIsNotEnoughRoomAbove() {
        android.graphics.Rect visible = new android.graphics.Rect(0, 24, 400, 800);
        assertEquals(new android.graphics.Rect(80, 24, 200, 104),
            TouchMouseOverlay.magnifierBounds(140, 168, 120, 80, 64, visible));
        assertEquals(new android.graphics.Rect(80, 231, 200, 311),
            TouchMouseOverlay.magnifierBounds(140, 167, 120, 80, 64, visible));
        assertEquals(new android.graphics.Rect(0, 88, 120, 168),
            TouchMouseOverlay.magnifierBounds(5, 24, 120, 80, 64, visible));
        assertEquals(new android.graphics.Rect(280, 646, 400, 726),
            TouchMouseOverlay.magnifierBounds(395, 790, 120, 80, 64, visible));
    }

    @Test
    public void moreOpensLocalMenuWithoutMouseEvents() {
        host.choose(2);
        assertTrue(host.contextMenuOpened);
        assertFalse(handler.isActive());
        assertTrue(host.mode.finished);
        assertTrue(actions.isEmpty());
    }

    @Test
    public void clickAndCancelHaveSeparateEffects() {
        host.choose(1);
        assertEquals(Arrays.asList(GhosttyTerminal.MOUSE_ACTION_PRESS,
            GhosttyTerminal.MOUSE_ACTION_RELEASE), actions);
        assertFalse(handler.isActive());
        assertFalse(host.contextMenuOpened);
    }

    @Test
    public void dismissingToolbarCancelsAim() {
        host.mode.finish();
        assertFalse(handler.isActive());
        assertTrue(actions.isEmpty());
        assertFalse(host.contextMenuOpened);
    }

    @Test
    public void cancelButtonSendsNothing() {
        host.choose(3);
        assertFalse(handler.isActive());
        assertTrue(actions.isEmpty());
        assertFalse(host.contextMenuOpened);
    }

    @Test
    public void hidingToolbarForDragDoesNotCancelOrReleaseIt() {
        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 150, 120, 0);
        handler.onTouchEvent(down);
        down.recycle();
        overlay.update();
        assertTrue(host.mode.finished);
        assertTrue(handler.isActive());
        assertTrue(actions.isEmpty());

        MotionEvent move = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 180, 120, 0);
        handler.onTouchEvent(move);
        move.recycle();
        overlay.update();
        assertEquals(Arrays.asList(GhosttyTerminal.MOUSE_ACTION_PRESS,
            GhosttyTerminal.MOUSE_ACTION_MOTION), actions);
        handler.cancel();
        overlay.update();
        assertEquals(Arrays.asList(GhosttyTerminal.MOUSE_ACTION_PRESS,
            GhosttyTerminal.MOUSE_ACTION_MOTION, GhosttyTerminal.MOUSE_ACTION_RELEASE), actions);
    }

    @Implements(SurfaceView.class)
    public static class SurfaceWithoutRenderer extends ShadowSurfaceView {
        private final SurfaceHolder holder = new FakeSurfaceHolder() {
            private final Surface surface = ReflectionHelpers.callConstructor(Surface.class);
            { surface.release(); }
            @Override public Surface getSurface() { return surface; }
        };

        @Implementation
        @Override protected SurfaceHolder getHolder() { return holder; }
    }

    /** Capture the platform toolbar contract without pretending to render its native popup. */
    private static final class ToolbarHost extends FrameLayout {
        TestActionMode mode;
        int type;
        boolean contextMenuOpened;

        ToolbarHost(Activity activity) { super(activity); }

        @Override
        public ActionMode startActionModeForChild(View child, ActionMode.Callback callback, int type) {
            this.type = type;
            mode = new TestActionMode(this, callback);
            callback.onCreateActionMode(mode, mode.getMenu());
            return mode;
        }

        @Override
        public boolean showContextMenuForChild(View child) {
            contextMenuOpened = true;
            return true;
        }

        void choose(int id) {
            mode.callback.onActionItemClicked(mode, mode.getMenu().findItem(id));
        }
    }

    private static final class TestActionMode extends ActionMode {
        final Callback callback;
        final Menu menu;
        boolean finished;

        TestActionMode(View host, Callback callback) {
            this.callback = callback;
            menu = new PopupMenu(host.getContext(), host).getMenu();
        }

        @Override public void finish() { finished = true; callback.onDestroyActionMode(this); }
        @Override public Menu getMenu() { return menu; }
        @Override public void invalidate() {}
        @Override public void setTitle(CharSequence title) {}
        @Override public void setTitle(int title) {}
        @Override public void setSubtitle(CharSequence subtitle) {}
        @Override public void setSubtitle(int subtitle) {}
        @Override public void setCustomView(View view) {}
        @Override public CharSequence getTitle() { return null; }
        @Override public CharSequence getSubtitle() { return null; }
        @Override public View getCustomView() { return null; }
        @Override public MenuInflater getMenuInflater() { return null; }
    }
}
