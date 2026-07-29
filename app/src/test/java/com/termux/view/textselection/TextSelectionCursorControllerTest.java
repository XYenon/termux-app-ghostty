package com.termux.view.textselection;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

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
}
