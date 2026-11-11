package com.termux.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.settings.properties.TermuxSharedProperties;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase;
import com.termux.terminal.TerminalSession;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowInputMethodManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, shadows = TerminalViewInputConnectionTest.RecordingInputMethodManager.class)
public class TerminalViewInputConnectionTest {

    @Test
    public void imeInputConfigurationDefaultsToEnabled() {
        assertTrue(TermuxPropertyConstants.TERMUX_DEFAULT_TRUE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST
            .contains(TermuxPropertyConstants.KEY_ENABLE_IME_INPUT));
        assertFalse(TermuxPropertyConstants.TERMUX_DEFAULT_FALSE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST
            .contains(TermuxPropertyConstants.KEY_ENABLE_IME_INPUT));
    }

    @Test
    public void xtChecksumReportPropertyDefaultsToDisabledAndCanBeEnabled() {
        String key = TermuxPropertyConstants.KEY_VT_XT_CHECKSUM_REPORT;
        assertTrue(TermuxPropertyConstants.TERMUX_DEFAULT_FALSE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST
            .contains(key));
        assertFalse(TermuxPropertyConstants.TERMUX_DEFAULT_TRUE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST
            .contains(key));
        assertEquals(false, TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(
            RuntimeEnvironment.getApplication(), key, null));
        assertEquals(true, TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(
            RuntimeEnvironment.getApplication(), key, "true"));
        assertEquals(false, TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(
            RuntimeEnvironment.getApplication(), key, "false"));
    }

    @Test
    public void advertisesNormalTextInputForImeComposition() {
        Context context = RuntimeEnvironment.getApplication();
        TerminalView terminalView = new TerminalView(context, null);
        terminalView.setTerminalViewClient(new TestTerminalViewClient(true, false));
        EditorInfo editorInfo = new EditorInfo();

        terminalView.onCreateInputConnection(editorInfo);

        assertEquals(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL,
            editorInfo.inputType);
        assertEquals(EditorInfo.IME_FLAG_NO_FULLSCREEN, editorInfo.imeOptions);
    }

    @Test
    public void preservesEnforcedCharBasedInputWhenImeInputIsDisabled() {
        assertEquals(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD |
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            createEditorInfo(false, true).inputType);
    }

    @Test
    public void preservesNullInputTypeWhenImeInputAndEnforcementAreDisabled() {
        assertEquals(InputType.TYPE_NULL, createEditorInfo(false, false).inputType);
    }

    @Test
    public void translatesRepeatedImeCursorMovesIntoTerminalArrowKeys() {
        InputFixture fixture = new InputFixture();
        InputConnection connection = fixture.connection;
        connection.commitText("abcd", 1);
        assertEquals(4, fixture.ime.selectionStart);
        assertEquals("", connection.getTextBeforeCursor(20, 0).toString());

        assertTrue(connection.setSelection(3, 3));
        assertEquals(3, fixture.ime.selectionStart);
        assertTrue(connection.setSelection(2, 2));
        assertTrue(connection.setSelection(4, 4));
        assertTrue(connection.setSelection(4, 4));
        assertEquals(Arrays.asList(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT), fixture.ime.keyDowns);
        assertEquals(4, fixture.ime.selectionStart);
        assertFalse(connection.setSelection(-1, -1));
        assertFalse(connection.setSelection(1, 3));
        assertEquals(4, fixture.ime.keyDowns.size());

        connection.setSelection(2, 2);
        connection.commitText("你", 1);
        connection.finishComposingText();
        assertEquals("abcd你", fixture.client.committed.toString());
        assertEquals(3, fixture.ime.selectionStart);
    }

    @Test
    public void keepsPreeditCursorMovementLocalAndReportsAbsolutePositions() {
        InputFixture fixture = new InputFixture();
        InputConnection connection = fixture.connection;
        connection.commitText("abc", 1);
        connection.beginBatchEdit();
        connection.setComposingText("nihao", 1);
        assertTrue(connection.setSelection(5, 5));
        assertEquals(3, fixture.ime.selectionStart);
        connection.endBatchEdit();

        assertEquals(5, fixture.ime.selectionStart);
        assertEquals(3, fixture.ime.composingStart);
        assertEquals(8, fixture.ime.composingEnd);
        assertTrue(fixture.ime.keyDowns.isEmpty());
        assertEquals("abc", fixture.client.committed.toString());
        assertEquals("ni", connection.getTextBeforeCursor(20, 0).toString());
        connection.setComposingRegion(3, 8);
        connection.commitText("你好", 1);
        connection.finishComposingText();

        assertEquals("abc你好", fixture.client.committed.toString());
        assertEquals(5, fixture.ime.selectionStart);
        assertEquals(-1, fixture.ime.composingStart);
        connection.setSelection(4, 4);
        assertEquals(Arrays.asList(KeyEvent.KEYCODE_DPAD_LEFT), fixture.ime.keyDowns);
    }

    @Test
    public void finishesPreeditOnlyOnceAndMovesFromTheCommittedPosition() {
        InputFixture fixture = new InputFixture();
        fixture.connection.commitText("ab", 1);
        fixture.connection.setComposingText("中文", 1);
        fixture.connection.finishComposingText();
        fixture.connection.finishComposingText();

        assertEquals("ab中文", fixture.client.committed.toString());
        assertEquals(4, fixture.ime.selectionStart);
        fixture.connection.setSelection(3, 3);
        assertEquals(Arrays.asList(KeyEvent.KEYCODE_DPAD_LEFT), fixture.ime.keyDowns);
    }

    @Test
    public void supportsLeftSwipesWithoutInputContextAndAfterBackspace() {
        InputFixture fixture = new InputFixture();
        assertTrue(fixture.connection.setSelection(-1, -1));
        assertTrue(fixture.connection.setSelection(-1, -1));
        assertEquals(0, fixture.ime.selectionStart);
        assertFalse(fixture.connection.setSelection(-2, -2));
        fixture.connection.commitText("abc", 1);
        fixture.connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
        assertEquals(2, fixture.ime.selectionStart);
        fixture.connection.setSelection(1, 1);
        fixture.connection.deleteSurroundingText(1, 0);
        assertEquals(0, fixture.ime.selectionStart);
        fixture.connection.setSelection(-1, -1);
        assertEquals(Arrays.asList(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_DPAD_LEFT), fixture.ime.keyDowns);
    }

    private static final class InputFixture {
        final TestTerminalViewClient client = new TestTerminalViewClient(true, false);
        final InputConnection connection;
        final RecordingInputMethodManager ime;

        InputFixture() {
            Context context = RuntimeEnvironment.getApplication();
            TerminalView view = new TerminalView(context, null);
            view.setTerminalViewClient(client);
            view.mTermSession = new TerminalSession("", "", new String[0], new String[0],
                100, new TermuxTerminalSessionClientBase());
            EditorInfo info = new EditorInfo();
            connection = view.onCreateInputConnection(info);
            assertEquals(0, info.initialSelStart);
            assertEquals(0, info.initialSelEnd);
            ime = Shadow.extract(context.getSystemService(Context.INPUT_METHOD_SERVICE));
        }
    }

    @Implements(InputMethodManager.class)
    public static class RecordingInputMethodManager extends ShadowInputMethodManager {
        final List<Integer> keyDowns = new ArrayList<>();
        int selectionStart;
        int composingStart = -1;
        int composingEnd = -1;

        @Implementation
        protected void dispatchKeyEventFromInputMethod(View target, KeyEvent event) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) keyDowns.add(event.getKeyCode());
        }

        @Implementation
        protected void updateSelection(View view, int start, int end, int candidatesStart,
                                       int candidatesEnd) {
            assertEquals(start, end);
            selectionStart = start;
            composingStart = candidatesStart;
            composingEnd = candidatesEnd;
        }
    }

    private EditorInfo createEditorInfo(boolean useImeInput, boolean enforceCharBasedInput) {
        Context context = RuntimeEnvironment.getApplication();
        TerminalView terminalView = new TerminalView(context, null);
        terminalView.setTerminalViewClient(
            new TestTerminalViewClient(useImeInput, enforceCharBasedInput));
        EditorInfo editorInfo = new EditorInfo();
        terminalView.onCreateInputConnection(editorInfo);
        return editorInfo;
    }

    private static final class TestTerminalViewClient extends TermuxTerminalViewClientBase {
        private final boolean useImeInput;
        private final boolean enforceCharBasedInput;
        final StringBuilder committed = new StringBuilder();

        TestTerminalViewClient(boolean useImeInput, boolean enforceCharBasedInput) {
            this.useImeInput = useImeInput;
            this.enforceCharBasedInput = enforceCharBasedInput;
        }

        @Override
        public boolean shouldUseImeInput() {
            return useImeInput;
        }

        @Override
        public boolean shouldEnforceCharBasedInput() {
            return enforceCharBasedInput;
        }

        @Override
        public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
            committed.appendCodePoint(codePoint);
            return true;
        }
    }
}
