package com.termux.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import com.termux.shared.termux.GhosttyShellIntegration;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;

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

    @Test
    public void shellIntegrationPreservesUserConfigAndDataDirectories() throws Exception {
        File resources = Files.createTempDirectory("ghostty-shell-env").toFile();
        HashMap<String, String> environment = new HashMap<>();
        environment.put("ZDOTDIR", "/custom/zsh");
        HashMap<String, String> original = new HashMap<>(environment);
        GhosttyShellIntegration.setupEnvironment(environment, resources);
        assertEquals(original, environment);

        assertTrue(new File(resources, "shell-integration").mkdirs());
        GhosttyShellIntegration.setupEnvironment(environment, resources);
        assertEquals("/custom/zsh", environment.get("ZDOTDIR"));
        assertEquals(resources.getAbsolutePath(), environment.get("GHOSTTY_RESOURCES_DIR"));
        assertEquals("", environment.get("GHOSTTY_SHELL_FEATURES"));
        String share = resources.getParentFile().getAbsolutePath();
        assertEquals(share + ":/usr/local/share:/usr/share", environment.get("XDG_DATA_DIRS"));
        environment.put("XDG_DATA_DIRS", "/custom/share:/second/share");
        GhosttyShellIntegration.setupEnvironment(environment, resources);
        assertEquals(share + ":/custom/share:/second/share", environment.get("XDG_DATA_DIRS"));
    }
}
