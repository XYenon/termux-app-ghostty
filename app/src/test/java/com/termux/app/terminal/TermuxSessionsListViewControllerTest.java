package com.termux.app.terminal;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.drawerlayout.widget.DrawerLayout;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession.TermuxSessionClient;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.ProgramStatusStore;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, qualifiers = "en-rUS-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TermuxSessionsListViewControllerTest {
    @Test
    public void statusSurvivesClientReplacementAndRecycledRowClearsIt() throws Exception {
        TermuxActivity activity = Robolectric.buildActivity(TermuxActivity.class).get();
        TerminalSession session = new TerminalSession("", "", new String[0], new String[0],
            100, new TermuxTerminalSessionClientBase());
        TerminalView terminalView = new TerminalView(activity, null);
        terminalView.mTermSession = session;
        ReflectionHelpers.setField(activity, "mTerminalView", terminalView);
        session.mSessionName = "Build";
        Constructor<TermuxSession> constructor = TermuxSession.class.getDeclaredConstructor(
            TerminalSession.class, ExecutionCommand.class, TermuxSessionClient.class, boolean.class);
        constructor.setAccessible(true);
        TermuxSessionsListViewController adapter = new TermuxSessionsListViewController(activity,
            Collections.singletonList(constructor.newInstance(session, null, null, false)));
        FrameLayout parent = new FrameLayout(activity);
        View row = adapter.getView(0, null, parent);
        TextView status = row.findViewById(R.id.session_program_status);
        assertEquals(View.GONE, status.getVisibility());
        capture(row, "program-status-default", 280);

        session.onProgramStatusReport(ProgramStatusStore.WORKING, 0, 37,
            "build/test", "cargo", "Tests", "Running 12 tests");
        session.updateTerminalSessionClient(new TermuxTerminalSessionClientBase());
        row = adapter.getView(0, row, parent);
        assertEquals(View.VISIBLE, status.getVisibility());
        assertTrue(status.getText().toString().contains("Working · 37% · cargo · build/test · Tests"));
        assertTrue(status.getContentDescription().toString().contains("Session 1"));
        capture(row, "program-status-working", 280);

        session.onProgramStatusReport(ProgramStatusStore.BLOCKED, ProgramStatusStore.KIND_PERMISSION,
            -1, "deploy", "terraform", "Production", "Apply the planned changes to the production deployment?");
        row = adapter.getView(0, row, parent);
        assertTrue(status.getText().toString().startsWith("Blocked · Approval needed"));
        capture(row, "program-status-blocked", 280);
        adapter.onItemClick(null, row, 0, 0);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        String details = ((TextView) dialog.findViewById(android.R.id.message)).getText().toString();
        assertTrue(details.contains("Apply the planned changes"));
        assertTrue(details.contains("Running 12 tests"));
        ShadowLooper.idleMainLooper();
        capture(dialog.getWindow().getDecorView(), "program-status-details", 360);
        dialog.dismiss();

        session.onProgramStatusReport(ProgramStatusStore.DONE, 0, -1,
            "build/test", "cargo", "Tests", "All tests passed");
        session.onProgramStatusPrompt();
        assertEquals(1, session.getProgramStatuses().size());
        row = adapter.getView(0, row, parent);
        capture(row, "program-status-done", 280);
        adapter.onItemClick(null, row, 0, 0);
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        ShadowLooper.idleMainLooper();
        assertTrue(session.getProgramStatuses().isEmpty());
        row = adapter.getView(0, row, parent);
        assertEquals(View.GONE, status.getVisibility());
        assertEquals("", status.getText().toString());
    }

    @Test
    public void entireRowSwitchesOtherSessionsAndShowsDetailsOnlyForCurrentSession() throws Exception {
        ActivityController<TermuxActivity> controller = Robolectric.buildActivity(TermuxActivity.class);
        TermuxActivity activity = controller.get();
        TerminalView terminalView = new TerminalView(activity, null);
        ReflectionHelpers.setField(activity, "mTerminalView", terminalView);
        ReflectionHelpers.setField(activity, "mTermuxTerminalSessionActivityClient",
            new TermuxTerminalSessionActivityClient(activity));
        TerminalSession first = new TerminalSession("", "", new String[0], new String[0],
            100, new TermuxTerminalSessionClientBase());
        TerminalSession second = new TerminalSession("", "", new String[0], new String[0],
            100, new TermuxTerminalSessionClientBase());
        first.onProgramStatusReport(ProgramStatusStore.WORKING, 0, 37,
            "build", "cargo", "Tests", "First session status");
        second.onProgramStatusReport(ProgramStatusStore.BLOCKED, ProgramStatusStore.KIND_PERMISSION,
            -1, "deploy", "terraform", "Deploy", "Second session status");
        Constructor<TermuxSession> constructor = TermuxSession.class.getDeclaredConstructor(
            TerminalSession.class, ExecutionCommand.class, TermuxSessionClient.class, boolean.class);
        constructor.setAccessible(true);
        TermuxSessionsListViewController adapter = new TermuxSessionsListViewController(activity,
            Arrays.asList(constructor.newInstance(first, null, null, false),
                constructor.newInstance(second, null, null, false)));
        ListView list = new ListView(activity);
        list.setAdapter(adapter);
        list.setOnItemClickListener(adapter);
        DrawerLayout drawer = new DrawerLayout(activity);
        drawer.setId(R.id.drawer_layout);
        drawer.addView(new FrameLayout(activity), new DrawerLayout.LayoutParams(400, 500));
        drawer.addView(list, new DrawerLayout.LayoutParams(280, 500, Gravity.LEFT));
        activity.setContentView(drawer);
        controller.visible();
        drawer.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY));
        drawer.layout(0, 0, 400, 500);

        // Exercise the title, status summary and row padding through actual list touch dispatch.
        for (int target : new int[]{R.id.session_title, R.id.session_program_status, 0}) {
            terminalView.mTermSession = first;
            drawer.openDrawer(Gravity.LEFT, false);
            tapSecondRow(list, target);
            assertSame(second, activity.getCurrentSession());
            assertFalse(drawer.isDrawerOpen(Gravity.LEFT));
            assertNull(ShadowAlertDialog.getLatestAlertDialog());
        }

        for (int target : new int[]{R.id.session_title, R.id.session_program_status, 0}) {
            drawer.openDrawer(Gravity.LEFT, false);
            tapSecondRow(list, target);
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertTrue(dialog.isShowing());
            String details = ((TextView) dialog.findViewById(android.R.id.message)).getText().toString();
            assertTrue(details.contains("Second session status"));
            assertFalse(details.contains("First session status"));
            assertSame(second, activity.getCurrentSession());
            assertTrue(drawer.isDrawerOpen(Gravity.LEFT));
            dialog.dismiss();
        }

        second.onProgramStatusPrompt();
        adapter.notifyDataSetChanged();
        list.measure(View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY));
        list.layout(0, 0, 280, 500);
        tapSecondRow(list, R.id.session_title);
        assertSame(second, activity.getCurrentSession());
        assertFalse(drawer.isDrawerOpen(Gravity.LEFT));
        assertFalse(ShadowAlertDialog.getLatestAlertDialog().isShowing());
    }

    private static void tapSecondRow(ListView list, int target) {
        View row = list.getChildAt(1);
        View area = target == 0 ? row : row.findViewById(target);
        float y = row.getTop() + (target == 0 ? row.getHeight() - 1
            : area.getTop() + area.getHeight() / 2f);
        long time = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 100, y, 0);
        MotionEvent up = MotionEvent.obtain(time, time + 20, MotionEvent.ACTION_UP, 100, y, 0);
        list.dispatchTouchEvent(down);
        list.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS);
        ((DrawerLayout) list.getParent()).computeScroll();
        ShadowLooper.idleMainLooper();
    }

    private static void capture(View view, String name, int width) throws Exception {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, width, view.getMeasuredHeight());
        assertTrue(view.getHeight() > 0);
        Bitmap bitmap = Bitmap.createBitmap(width * 2, view.getHeight() * 2, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(android.graphics.Color.WHITE);
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
