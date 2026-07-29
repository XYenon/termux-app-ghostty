package com.termux.app;

import static org.junit.Assert.assertEquals;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import org.junit.Test;

public class TermuxShellEnvironmentTest {

    @Test
    public void selectsGhosttyTermOnlyWhenTerminfoIsAvailable() {
        assertEquals("xterm-ghostty",
            TermuxShellEnvironment.getTermValue(false, true));
        assertEquals("xterm-256color",
            TermuxShellEnvironment.getTermValue(false, false));
        assertEquals("xterm-256color",
            TermuxShellEnvironment.getTermValue(true, true));
    }
}
