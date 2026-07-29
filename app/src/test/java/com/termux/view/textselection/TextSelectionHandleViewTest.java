package com.termux.view.textselection;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.view.MotionEvent;

import com.termux.view.TerminalView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class TextSelectionHandleViewTest {

    @Test
    public void draggingRefreshesHandlesAfterUpdatingSelection() {
        Context context = RuntimeEnvironment.getApplication();
        TerminalView terminalView = new TerminalView(context, null);
        RecordingCursorController controller = new RecordingCursorController();
        TextSelectionHandleView handle = new TextSelectionHandleView(
            terminalView, controller, TextSelectionHandleView.RIGHT);

        handle.onTouchEvent(MotionEvent.obtain(
            0, 0, MotionEvent.ACTION_DOWN, 10, 10, 0));
        handle.onTouchEvent(MotionEvent.obtain(
            0, 16, MotionEvent.ACTION_MOVE, 30, 40, 0));

        assertEquals(Arrays.asList("update", "render"), controller.calls);
    }

    private static final class RecordingCursorController implements CursorController {
        final List<String> calls = new ArrayList<>();

        @Override
        public void show(MotionEvent event) {
        }

        @Override
        public boolean hide() {
            return false;
        }

        @Override
        public void render() {
            calls.add("render");
        }

        @Override
        public void updatePosition(TextSelectionHandleView handle, int x, int y) {
            calls.add("update");
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            return false;
        }

        @Override
        public void onTouchModeChanged(boolean isInTouchMode) {
        }

        @Override
        public void onDetached() {
        }

        @Override
        public boolean isActive() {
            return true;
        }
    }
}
