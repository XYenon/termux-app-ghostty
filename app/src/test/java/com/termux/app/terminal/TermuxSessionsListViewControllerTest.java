package com.termux.app.terminal;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession.TermuxSessionClient;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.ProgramStatusStore;
import com.termux.terminal.TerminalSession;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.util.Collections;

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
        assertTrue(status.performClick());
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
        assertTrue(status.performClick());
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        ShadowLooper.idleMainLooper();
        assertTrue(session.getProgramStatuses().isEmpty());
        row = adapter.getView(0, row, parent);
        assertEquals(View.GONE, status.getVisibility());
        assertEquals("", status.getText().toString());
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
