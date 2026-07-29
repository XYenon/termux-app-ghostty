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
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;

final class GhosttyTerminfoInstaller {

    private static final String LOG_TAG = "GhosttyTerminfoInstaller";

    static final String ASSET_PATH = "ghostty/terminfo/x/xterm-ghostty";

    private GhosttyTerminfoInstaller() {
    }

    static synchronized boolean installIfNeeded(Context context) {
        File prefix = TermuxConstants.TERMUX_PREFIX_DIR;
        if (!prefix.isDirectory())
            return false;

        return install(context, new File(GhosttyTerminfo.TERMINFO_FILE_PATH));
    }

    static synchronized boolean install(Context context, File destination) {
        File parent = destination.getParentFile();
        File temporary = new File(parent, destination.getName() + ".tmp");

        try {
            byte[] assetDigest;
            try (InputStream input = context.getAssets().open(ASSET_PATH)) {
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

            try (InputStream input = context.getAssets().open(ASSET_PATH);
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

            Logger.logInfo(LOG_TAG, "Installed " + GhosttyTerminfo.TERM + " terminfo at \"" +
                destination + "\".");
            return true;
        } catch (Exception e) {
            temporary.delete();
            Logger.logStackTraceWithMessage(LOG_TAG,
                "Failed to install " + GhosttyTerminfo.TERM + " terminfo", e);
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
