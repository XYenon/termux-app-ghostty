package com.termux.app.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class TermuxTerminalViewClientTest {

    @Test
    public void extractsPlainUrlAtTap() {
        assertEquals(
            "https://example.com/path?q=1",
            TermuxTerminalViewClient.getUrlAtTap(
                "prefix:https://example.com/path?q=1"));
        assertNull(TermuxTerminalViewClient.getUrlAtTap(""));
        assertNull(TermuxTerminalViewClient.getUrlAtTap(null));
        assertNull(TermuxTerminalViewClient.getUrlAtTap("plain-text"));
    }
}
