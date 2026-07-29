package com.termux.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.MotionEvent;

import com.termux.terminal.GhosttyTerminal;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class TouchMouseDragHandlerTest {

    @Test
    public void inactiveHandlerDoesNotConsumeScrollingGesture() {
        List<Integer> actions = new ArrayList<>();
        TouchMouseDragHandler handler = new TouchMouseDragHandler(
            (event, action) -> actions.add(action));

        assertFalse(handler.onTouchEvent(
            event(MotionEvent.ACTION_DOWN, 10, 20)));
        assertFalse(handler.onTouchEvent(
            event(MotionEvent.ACTION_MOVE, 10, 40)));
        assertFalse(handler.onTouchEvent(
            event(MotionEvent.ACTION_UP, 10, 40)));

        assertTrue(actions.isEmpty());
    }

    @Test
    public void longPressDragSendsPressMotionAndRelease() {
        List<MouseEvent> events = new ArrayList<>();
        TouchMouseDragHandler handler = new TouchMouseDragHandler(
            (event, action) -> events.add(new MouseEvent(action,
                event.getX(), event.getY())));

        handler.start(event(MotionEvent.ACTION_DOWN, 10, 20));
        assertTrue(handler.onTouchEvent(
            event(MotionEvent.ACTION_MOVE, 30, 40)));
        assertTrue(handler.onTouchEvent(
            event(MotionEvent.ACTION_MOVE, 50, 60)));
        assertTrue(handler.onTouchEvent(
            event(MotionEvent.ACTION_UP, 70, 80)));

        assertFalse(handler.isActive());
        assertEquals(Arrays.asList(
            new MouseEvent(GhosttyTerminal.MOUSE_ACTION_PRESS, 10, 20),
            new MouseEvent(GhosttyTerminal.MOUSE_ACTION_MOTION, 30, 40),
            new MouseEvent(GhosttyTerminal.MOUSE_ACTION_MOTION, 50, 60),
            new MouseEvent(GhosttyTerminal.MOUSE_ACTION_RELEASE, 70, 80)
        ), events);
    }

    @Test
    public void cancelReleasesActiveDrag() {
        List<Integer> actions = new ArrayList<>();
        TouchMouseDragHandler handler = new TouchMouseDragHandler(
            (event, action) -> actions.add(action));

        handler.start(event(MotionEvent.ACTION_DOWN, 10, 20));
        handler.start(event(MotionEvent.ACTION_DOWN, 30, 40));
        assertTrue(handler.onTouchEvent(
            event(MotionEvent.ACTION_CANCEL, 50, 60)));
        assertFalse(handler.onTouchEvent(
            event(MotionEvent.ACTION_MOVE, 70, 80)));

        assertFalse(handler.isActive());
        assertEquals(Arrays.asList(
            GhosttyTerminal.MOUSE_ACTION_PRESS,
            GhosttyTerminal.MOUSE_ACTION_RELEASE
        ), actions);
    }

    private static MotionEvent event(int action, float x, float y) {
        return MotionEvent.obtain(0, 0, action, x, y, 0);
    }

    private static final class MouseEvent {
        final int action;
        final float x;
        final float y;

        MouseEvent(int action, float x, float y) {
            this.action = action;
            this.x = x;
            this.y = y;
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof MouseEvent)) return false;
            MouseEvent other = (MouseEvent) object;
            return action == other.action && x == other.x && y == other.y;
        }

        @Override
        public int hashCode() {
            int result = action;
            result = 31 * result + Float.floatToIntBits(x);
            return 31 * result + Float.floatToIntBits(y);
        }
    }
}
