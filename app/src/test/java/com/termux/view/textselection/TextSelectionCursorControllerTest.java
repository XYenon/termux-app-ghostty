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

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class TextSelectionCursorControllerTest {

    @Test
    public void scrollsOnlyWhenHandleLeavesViewport() {
        assertEquals(-1,
            TextSelectionCursorController.getViewportScrollForRow(-1, 24));
        assertEquals(0,
            TextSelectionCursorController.getViewportScrollForRow(0, 24));
        assertEquals(0,
            TextSelectionCursorController.getViewportScrollForRow(23, 24));
        assertEquals(1,
            TextSelectionCursorController.getViewportScrollForRow(24, 24));
        assertEquals(0,
            TextSelectionCursorController.getViewportScrollForRow(1, 0));
    }

    @Test
    public void shiftsBothSelectionEndpointsWithViewport() {
        Context context = RuntimeEnvironment.getApplication();
        TextSelectionCursorController controller =
            new TextSelectionCursorController(new TerminalView(context, null));
        MotionEvent event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN,
            40, 90, 0);
        controller.setInitialTextSelectionPosition(event);
        event.recycle();
        int[] before = new int[4];
        int[] after = new int[4];
        controller.getSelectors(before);

        controller.shiftSelectionRows(3);
        controller.getSelectors(after);

        assertEquals(before[0] + 3, after[0]);
        assertEquals(before[1] + 3, after[1]);
        assertEquals(before[2], after[2]);
        assertEquals(before[3], after[3]);
    }
}
