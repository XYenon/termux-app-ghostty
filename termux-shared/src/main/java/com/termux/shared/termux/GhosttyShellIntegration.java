package com.termux.shared.termux;

import java.io.File;
import java.util.Map;

/** Environment shared by the system shell integration loaders. */
public final class GhosttyShellIntegration {
    public static final String RESOURCES_DIR = TermuxConstants.TERMUX_SHARE_PREFIX_DIR_PATH + "/ghostty";

    private GhosttyShellIntegration() {
    }

    public static void setupEnvironment(Map<String, String> environment, File resources) {
        if (!new File(resources, "shell-integration").isDirectory())
            return;
        environment.put("GHOSTTY_RESOURCES_DIR", resources.getAbsolutePath());
        // Keep prompt/cwd reporting; desktop wrappers need the Ghostty CLI.
        environment.put("GHOSTTY_SHELL_FEATURES", "");
        // Nushell vendor autoload and Elvish module discovery use this directory.
        String data = resources.getParentFile().getAbsolutePath();
        String previous = environment.get("XDG_DATA_DIRS");
        environment.put("XDG_DATA_DIRS", data + ":" +
            (previous == null || previous.isEmpty() ? "/usr/local/share:/usr/share" : previous));
    }
}
