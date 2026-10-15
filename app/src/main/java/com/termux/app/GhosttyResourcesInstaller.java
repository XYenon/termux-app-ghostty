package com.termux.app;

import android.content.Context;
import android.system.Os;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.GhosttyTerminfo;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;

final class GhosttyResourcesInstaller {

    private static final String LOG_TAG = "GhosttyResourcesInstaller";

    static final String ASSET_PATH = "ghostty/terminfo/x/xterm-ghostty";

    private GhosttyResourcesInstaller() {
    }

    static synchronized boolean installIfNeeded(Context context) {
        File prefix = TermuxConstants.TERMUX_PREFIX_DIR;
        if (!prefix.isDirectory())
            return false;

        boolean terminfo = install(context, new File(GhosttyTerminfo.TERMINFO_FILE_PATH));
        boolean integration = installShellIntegration(context, prefix);
        return terminfo && integration;
    }

    static synchronized boolean install(Context context, File destination) {
        return installAsset(context, ASSET_PATH, destination);
    }

    static synchronized boolean installShellIntegration(Context context, File prefix) {
        File resources = new File(prefix, "share/ghostty");
        boolean installed = true;
        for (String path : new String[]{
            "bash/ghostty.bash", "bash/bash-preexec.sh",
            "zsh/zshenv", "zsh/ghostty-integration",
            "fish/vendor_conf.d/ghostty-shell-integration.fish",
            "elvish/lib/ghostty-integration.elv",
            "nushell/vendor/autoload/ghostty.nu"}) {
            String destination = path.equals("zsh/zshenv") ? "zsh/.zshenv" : path;
            installed &= installAsset(context, "ghostty/shell-integration/" + path,
                new File(resources, "shell-integration/" + destination));
        }
        for (String[] loader : new String[][]{
            {"termux-ghostty.fish", "etc/fish/conf.d/termux-ghostty.fish"},
            {"termux-ghostty.nu", "share/nushell/vendor/autoload/termux-ghostty.nu"},
            {"elvish/lib/ghostty-integration.elv", "share/elvish/lib/ghostty-integration.elv"}}) {
            installed &= installAsset(context, "ghostty/shell-integration/" + loader[0],
                new File(prefix, loader[1]));
        }
        installed &= installRcLoader(new File(prefix, "etc/bash.bashrc"),
            "bash/ghostty.bash");
        installed &= installRcLoader(new File(prefix, "etc/zshrc"),
            "zsh/ghostty-integration");
        return installed;
    }

    static synchronized boolean installRcLoader(File destination, String loader) {
        // Do not preempt a shell package's conffile before that shell is installed.
        if (!destination.isFile())
            return true;
        File parent = destination.getParentFile();
        File temporary = new File(parent, destination.getName() + ".ghostty.tmp");
        try {
            String hook = "\n# Termux Ghostty shell integration (managed by the app).\n" +
                "if [ -n \"${GHOSTTY_RESOURCES_DIR-}\" ] &&\n" +
                "   [ -r \"$GHOSTTY_RESOURCES_DIR/shell-integration/" + loader + "\" ]; then\n" +
                "    . \"$GHOSTTY_RESOURCES_DIR/shell-integration/" + loader + "\"\nfi\n";
            byte[] existing = Files.readAllBytes(destination.toPath());
            if (new String(existing, StandardCharsets.ISO_8859_1).contains(hook))
                return true;
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(existing);
                output.write(hook.getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            //noinspection OctalInteger
            Os.chmod(temporary.getAbsolutePath(), 0600);
            Files.move(temporary.toPath(), destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            temporary.delete();
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to install system rc loader at " + destination, e);
            return false;
        }
    }

    private static boolean installAsset(Context context, String assetPath, File destination) {
        File parent = destination.getParentFile();
        File temporary = new File(parent, destination.getName() + ".tmp");

        try {
            byte[] assetDigest;
            try (InputStream input = context.getAssets().open(assetPath)) {
                assetDigest = digest(input);
            }

            if (destination.isFile()) {
                try (InputStream input = new FileInputStream(destination)) {
                    if (Arrays.equals(assetDigest, digest(input)))
                        return true;
                }
            }

            if (!parent.isDirectory() && !parent.mkdirs())
                throw new IllegalStateException("Failed to create " + parent);

            try (InputStream input = context.getAssets().open(assetPath);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1)
                    output.write(buffer, 0, count);
                output.getFD().sync();
            }

            //noinspection OctalInteger
            Os.chmod(temporary.getAbsolutePath(), 0600);
            Files.move(temporary.toPath(), destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);

            Logger.logInfo(LOG_TAG, "Installed " + assetPath + " at \"" +
                destination + "\".");
            return true;
        } catch (Exception e) {
            temporary.delete();
            Logger.logStackTraceWithMessage(LOG_TAG,
                "Failed to install " + assetPath, e);
            return destination.isFile();
        }
    }

    private static byte[] digest(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1)
            digest.update(buffer, 0, count);
        return digest.digest();
    }
}
