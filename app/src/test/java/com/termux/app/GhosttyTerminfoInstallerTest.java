package com.termux.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class GhosttyTerminfoInstallerTest {

    @Test
    public void installsAndRepairsCompiledTerminfoAtomically() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File root = Files.createTempDirectory("ghostty-terminfo").toFile();
        File destination = new File(root, "terminfo/x/xterm-ghostty");
        byte[] expected = readAll(
            context.getAssets().open(GhosttyTerminfoInstaller.ASSET_PATH));

        assertTrue(GhosttyTerminfoInstaller.install(context, destination));
        assertArrayEquals(expected, readAll(new FileInputStream(destination)));
        int magic = (expected[0] & 0xff) | ((expected[1] & 0xff) << 8);
        assertTrue(magic == 0x011a || magic == 0x021e);
        assertTrue(new String(expected, StandardCharsets.ISO_8859_1)
            .contains("xterm-ghostty"));

        long modified = destination.lastModified();
        assertTrue(GhosttyTerminfoInstaller.install(context, destination));
        assertEquals(modified, destination.lastModified());

        try (FileOutputStream output = new FileOutputStream(destination)) {
            output.write("broken".getBytes(StandardCharsets.US_ASCII));
        }
        assertTrue(GhosttyTerminfoInstaller.install(context, destination));
        assertArrayEquals(expected, readAll(new FileInputStream(destination)));
    }

    private static byte[] readAll(InputStream input) throws Exception {
        try (InputStream source = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = source.read(buffer)) != -1)
                output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }
}
