package com.termux.view;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.view.PointerIcon;

import com.termux.terminal.GhosttyTerminal;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class TerminalViewMouseShapeTest {

    @Test
    public void mapsGhosttyPointerShapesToAndroidPointerIcons() {
        Context context = RuntimeEnvironment.getApplication();
        TerminalView terminalView = new TerminalView(context, null);

        assertPointerIcon(terminalView, GhosttyTerminal.MOUSE_SHAPE_POINTER,
            PointerIcon.TYPE_HAND);
        assertPointerIcon(terminalView, GhosttyTerminal.MOUSE_SHAPE_TEXT,
            PointerIcon.TYPE_TEXT);
        assertPointerIcon(terminalView, GhosttyTerminal.MOUSE_SHAPE_CROSSHAIR,
            PointerIcon.TYPE_CROSSHAIR);
        assertPointerIcon(terminalView, GhosttyTerminal.MOUSE_SHAPE_GRAB,
            PointerIcon.TYPE_GRAB);
        assertPointerIcon(terminalView, GhosttyTerminal.MOUSE_SHAPE_EW_RESIZE,
            PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW);
    }

    private static void assertPointerIcon(TerminalView terminalView,
                                          int ghosttyShape, int androidType) {
        terminalView.setMouseShape(ghosttyShape);

        assertEquals(ghosttyShape, terminalView.getMouseShapeForTest());
        assertEquals(androidType,
            TerminalView.getAndroidPointerIconType(ghosttyShape));
    }
}
