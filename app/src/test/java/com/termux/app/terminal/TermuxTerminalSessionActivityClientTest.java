package com.termux.app.terminal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Random;

public class TermuxTerminalSessionActivityClientTest {

    @Test
    public void sanitizeOscNotificationTextRemovesControls() {
        assertNull(TermuxTerminalSessionActivityClient.sanitizeOscNotificationText(null));
        assertEquals("title\nbody\tok\u4e2d",
            TermuxTerminalSessionActivityClient.sanitizeOscNotificationText(
                "\u0000title\nbody\tok\u007f\u0085\u009f\u4e2d"));
    }

    @Test
    public void sanitizeOscNotificationTextDoesNotSplitSurrogatePair() {
        StringBuilder input = new StringBuilder();
        for (int i = 0; i < 4095; i++) input.append('a');
        input.append("\ud83d\ude00");
        assertEquals(4095, TermuxTerminalSessionActivityClient
            .sanitizeOscNotificationText(input.toString()).length());

        input.setLength(4094);
        input.append("\ud83d\ude00");
        String output = TermuxTerminalSessionActivityClient
            .sanitizeOscNotificationText(input.toString());
        assertEquals(4096, output.length());
        assertEquals(0x1f600, output.codePointAt(4094));
    }

    @Test
    public void readClipboardBytesFuzzesChunking() throws Exception {
        Random random = new Random(0x52c1a0bL);
        for (int iteration = 0; iteration < 5000; iteration++) {
            byte[] expected = new byte[random.nextInt(64 * 1024)];
            random.nextBytes(expected);
            assertArrayEquals(expected,
                TermuxTerminalSessionActivityClient.readClipboardBytes(
                    new ChunkedInputStream(expected, random.nextLong())));
        }
    }

    @Test
    public void readClipboardBytesAcceptsLimit() throws Exception {
        byte[] expected =
            new byte[TermuxTerminalSessionActivityClient.MAX_OSC_CLIPBOARD_BYTES];
        new Random(52).nextBytes(expected);
        assertArrayEquals(expected,
            TermuxTerminalSessionActivityClient.readClipboardBytes(
                new ByteArrayInputStream(expected)));
    }

    @Test
    public void readClipboardBytesRejectsAboveLimit() throws Exception {
        InputStream input = new RepeatingInputStream(
            TermuxTerminalSessionActivityClient.MAX_OSC_CLIPBOARD_BYTES + 1);
        try {
            TermuxTerminalSessionActivityClient.readClipboardBytes(input);
            fail("Expected an IOException for oversized clipboard data");
        } catch (IOException e) {
            assertEquals("OSC clipboard content is too large", e.getMessage());
        }
    }

    @Test
    public void encodeClipboardTextFuzzesUtf8Length() throws Exception {
        Random random = new Random(0x52defacedL);
        String[] alphabet = {"a", "\u4e2d", "\ud83d\ude00", "\u0301", "\0"};
        for (int iteration = 0; iteration < 5000; iteration++) {
            StringBuilder text = new StringBuilder();
            int count = random.nextInt(4096);
            for (int i = 0; i < count; i++)
                text.append(alphabet[random.nextInt(alphabet.length)]);
            assertArrayEquals(text.toString().getBytes("UTF-8"),
                TermuxTerminalSessionActivityClient.encodeClipboardText(text));
        }
    }

    @Test
    public void encodeClipboardTextRejectsUtf8ExpansionAboveLimit() throws Exception {
        StringBuilder text = new StringBuilder();
        while (text.length() <=
               TermuxTerminalSessionActivityClient.MAX_OSC_CLIPBOARD_BYTES / 2)
            text.append("\ud83d\ude00");
        try {
            TermuxTerminalSessionActivityClient.encodeClipboardText(text);
            fail("Expected an IOException for oversized UTF-8 clipboard text");
        } catch (IOException e) {
            assertEquals("OSC clipboard content is too large", e.getMessage());
        }
    }

    private static final class ChunkedInputStream extends InputStream {
        private final byte[] data;
        private final Random random;
        private int offset;
        private boolean returnZero;

        ChunkedInputStream(byte[] data, long seed) {
            this.data = data;
            this.random = new Random(seed);
        }

        @Override
        public int read(byte[] buffer, int off, int len) {
            if (offset == data.length) return -1;
            if (returnZero) {
                returnZero = false;
                return 0;
            }
            returnZero = random.nextInt(8) == 0;
            int count = Math.min(
                Math.min(len, data.length - offset),
                1 + random.nextInt(Math.min(4096, data.length - offset)));
            System.arraycopy(data, offset, buffer, off, count);
            offset += count;
            return count;
        }

        @Override
        public int read() {
            return offset == data.length ? -1 : data[offset++] & 0xff;
        }
    }

    private static final class RepeatingInputStream extends InputStream {
        private int remaining;

        RepeatingInputStream(int remaining) {
            this.remaining = remaining;
        }

        @Override
        public int read(byte[] buffer, int off, int len) {
            if (remaining == 0) return -1;
            int count = Math.min(len, remaining);
            remaining -= count;
            return count;
        }

        @Override
        public int read() {
            if (remaining == 0) return -1;
            remaining--;
            return 0;
        }
    }
}
