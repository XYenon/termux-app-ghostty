package com.termux.view;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TerminalViewScrollbarTest {

    @Test
    public void usesGhosttyViewportOffset() {
        assertEquals(1, TerminalView.resolveVerticalScrollOffset(false, 0));
        assertEquals(0, TerminalView.resolveVerticalScrollOffset(true, 0));
        assertEquals(37, TerminalView.resolveVerticalScrollOffset(true, 37));
        assertEquals(100, TerminalView.resolveVerticalScrollOffset(true, 100));
    }
}
