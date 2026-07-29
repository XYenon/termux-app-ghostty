package com.termux.shared.termux;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

public final class GhosttyTerminfo {

    public static final String TERM = "xterm-ghostty";
    public static final String TERMINFO_FILE_PATH =
        TermuxConstants.TERMUX_SHARE_PREFIX_DIR_PATH + "/terminfo/x/xterm-ghostty";

    private GhosttyTerminfo() {
    }

    public static boolean isInstalled() {
        File file = new File(TERMINFO_FILE_PATH);
        if (!file.isFile())
            return false;

        byte[] header = new byte[256];
        try (FileInputStream input = new FileInputStream(file)) {
            int count = input.read(header);
            if (count < 16)
                return false;
            int magic = (header[0] & 0xff) | ((header[1] & 0xff) << 8);
            if (magic != 0x011a && magic != 0x021e)
                return false;
            return new String(header, 0, count, StandardCharsets.ISO_8859_1)
                .contains(TERM);
        } catch (Exception ignored) {
            return false;
        }
    }
}
