# ghostty_engine.cpp resolves these TerminalOutput callbacks through
# GetMethodID. They have no statically visible Java callers and are otherwise
# removed from a minified application.
-keepclassmembers class * extends com.termux.terminal.TerminalOutput {
    public void write(byte[], int, int);
    public void titleChanged(java.lang.String, java.lang.String);
    public void workingDirectoryChanged(java.lang.String);
    public void onMouseShapeChanged(int);
    public void onDesktopNotification(java.lang.String, java.lang.String);
    public void onProgressReport(int, int);
    public int onOscClipboard(int, java.lang.String, byte[], boolean);
    public byte[] onOscClipboardRead(int);
    public void onBell();
    public void onColorsChanged();
}
