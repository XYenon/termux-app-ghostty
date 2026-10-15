package com.termux.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
public class GhosttyResourcesInstallerTest {

    @Test
    public void installsAndRepairsCompiledTerminfoAtomically() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File root = Files.createTempDirectory("ghostty-terminfo").toFile();
        File destination = new File(root, "terminfo/x/xterm-ghostty");
        byte[] expected = readAll(
            context.getAssets().open(GhosttyResourcesInstaller.ASSET_PATH));

        assertTrue(GhosttyResourcesInstaller.install(context, destination));
        assertArrayEquals(expected, readAll(new FileInputStream(destination)));
        int magic = (expected[0] & 0xff) | ((expected[1] & 0xff) << 8);
        assertTrue(magic == 0x011a || magic == 0x021e);
        assertTrue(new String(expected, StandardCharsets.ISO_8859_1)
            .contains("xterm-ghostty"));

        long modified = destination.lastModified();
        assertTrue(GhosttyResourcesInstaller.install(context, destination));
        assertEquals(modified, destination.lastModified());

        try (FileOutputStream output = new FileOutputStream(destination)) {
            output.write("broken".getBytes(StandardCharsets.US_ASCII));
        }
        assertTrue(GhosttyResourcesInstaller.install(context, destination));
        assertArrayEquals(expected, readAll(new FileInputStream(destination)));
    }

    @Test
    public void installsAndRepairsAllShellIntegrations() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File prefix = Files.createTempDirectory("ghostty-shell").toFile();
        File resources = new File(prefix, "share/ghostty");
        assertTrue(new File(prefix, "etc").mkdirs());
        for (String name : new String[]{"bash.bashrc", "zshrc"})
            Files.write(new File(prefix, "etc/" + name).toPath(), "# existing rc\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(GhosttyResourcesInstaller.installShellIntegration(context, prefix));
        for (String path : new String[]{
            "shell-integration/zsh/zshenv", "shell-integration/zsh/ghostty-integration",
            "shell-integration/bash/ghostty.bash", "shell-integration/bash/bash-preexec.sh",
            "shell-integration/fish/vendor_conf.d/ghostty-shell-integration.fish",
            "shell-integration/elvish/lib/ghostty-integration.elv",
            "shell-integration/nushell/vendor/autoload/ghostty.nu"}) {
            File destination = new File(resources,
                path.equals("shell-integration/zsh/zshenv") ? "shell-integration/zsh/.zshenv" : path);
            byte[] expected = readAll(context.getAssets().open("ghostty/" + path));
            assertArrayEquals(expected, readAll(new FileInputStream(destination)));
            try (FileOutputStream output = new FileOutputStream(destination)) {
                output.write("broken".getBytes(StandardCharsets.US_ASCII));
            }
            assertTrue(GhosttyResourcesInstaller.installShellIntegration(context, prefix));
            assertArrayEquals(expected, readAll(new FileInputStream(destination)));
        }
        for (String[] loader : new String[][]{
            {"termux-ghostty.fish", "etc/fish/conf.d/termux-ghostty.fish"},
            {"termux-ghostty.nu", "share/nushell/vendor/autoload/termux-ghostty.nu"},
            {"elvish/lib/ghostty-integration.elv", "share/elvish/lib/ghostty-integration.elv"}}) {
            assertArrayEquals(readAll(context.getAssets().open("ghostty/shell-integration/" + loader[0])),
                Files.readAllBytes(new File(prefix, loader[1]).toPath()));
        }
        for (String[] rc : new String[][]{{"bash.bashrc", "bash/ghostty.bash"}, {"zshrc", "zsh/ghostty-integration"}}) {
            String contents = new String(Files.readAllBytes(new File(prefix, "etc/" + rc[0]).toPath()),
                StandardCharsets.UTF_8);
            assertTrue(contents.startsWith("# existing rc\n"));
            String source = ". \"$GHOSTTY_RESOURCES_DIR/shell-integration/" + rc[1] + "\"";
            assertTrue(contents.contains(source));
            assertEquals(contents.indexOf(source), contents.lastIndexOf(source));
        }
        assertFalse(new File(prefix, "etc/bashrc.d").exists());
        assertFalse(new File(prefix, "etc/zshrc.d").exists());
    }

    @Test
    public void preservesSystemRcAndDoesNotPreemptMissingShellPackages() throws Exception {
        File root = Files.createTempDirectory("ghostty-system-rc").toFile();
        File hook = new File(root, "zshrc");
        String loader = "zsh/ghostty-integration";
        assertTrue(GhosttyResourcesInstaller.installRcLoader(hook, loader));
        assertFalse(hook.exists());
        String existing = "# existing system rc\nexport SYSTEM_RC_LOADED=yes\n";
        Files.write(hook.toPath(), existing.getBytes(StandardCharsets.UTF_8));
        assertTrue(GhosttyResourcesInstaller.installRcLoader(hook, loader));
        String installed = new String(Files.readAllBytes(hook.toPath()), StandardCharsets.UTF_8);
        assertTrue(installed.startsWith(existing));
        assertTrue(installed.contains(". \"$GHOSTTY_RESOURCES_DIR/shell-integration/" + loader + "\""));
        assertTrue(GhosttyResourcesInstaller.installRcLoader(hook, loader));
        assertEquals(installed, new String(Files.readAllBytes(hook.toPath()), StandardCharsets.UTF_8));
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
