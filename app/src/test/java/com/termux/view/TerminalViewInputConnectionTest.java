package com.termux.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;

import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class TerminalViewInputConnectionTest {

    @Test
    public void imeInputConfigurationDefaultsToEnabled() {
        assertTrue(TermuxPropertyConstants.TERMUX_DEFAULT_TRUE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST
            .contains(TermuxPropertyConstants.KEY_ENABLE_IME_INPUT));
        assertFalse(TermuxPropertyConstants.TERMUX_DEFAULT_FALSE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST
            .contains(TermuxPropertyConstants.KEY_ENABLE_IME_INPUT));
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
    }
}
