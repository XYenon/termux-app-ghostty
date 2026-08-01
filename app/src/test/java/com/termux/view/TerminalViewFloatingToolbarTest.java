package com.termux.view;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TerminalViewFloatingToolbarTest {

    @Test
    public void hidesOnlyWhileSelectionHandleIsDragged() {
        assertFalse(TerminalView.shouldHideFloatingToolbarForMove(false, false));
        assertTrue(TerminalView.shouldHideFloatingToolbarForMove(true, false));
        assertTrue(TerminalView.shouldHideFloatingToolbarForMove(false, true));
    }
}
