# Ghostty upstream integration

Ghostty is fetched source code, not a Git submodule or a vendored tree. The
repository and immutable revision are declared in `build.gradle`.
`prepareNativeSources` checks out that revision under `build/native-sources`,
and `buildGhosttyAndroid` clones it into the terminal emulator build directory,
applies `native/patches/ghostty-android.patch`, and builds `libghostty-vt` for
the configured Android ABIs. Keep the patch limited to C APIs needed by the
Android JNI and Vulkan renderer.

## 2026-10-06 sync

The pin moved from `c959af63d11b524a84c21900372990dbc024b059` to
`c3203ea4b169a18eb2ccfe92847e426d8afea858`, the upstream `main` HEAD
queried for this sync. The 135-commit range (including merge commits) was
reviewed by commit list and source diff ([upstream comparison](https://github.com/ghostty-org/ghostty/compare/c959af63d11b524a84c21900372990dbc024b059...c3203ea4b169a18eb2ccfe92847e426d8afea858)).

Android integration changes:

- OSC 22 mouse shapes now use upstream `GhosttyMouseShape` and
  `GHOSTTY_TERMINAL_DATA_MOUSE_SHAPE`. The duplicate Android enum and Zig
  getter were removed from the patch. The numeric values match the existing
  Java pointer mapping, so touch aiming and link actions keep working. Empty
  OSC 22 now restores the text pointer through the upstream fix.
- The new RIS reset callback clears cached title and working-directory values
  and pending notification state. JNI publishes the final state after releasing
  the native lock, including when a reset and a new title arrive in the same
  PTY read. Explicit application resets use the same host-state cleanup.
- Android memory-pressure callbacks request bounded scrollback compression on
  a background worker. The new memory-usage query prioritizes terminals with
  the largest resident footprint. History remains available and decompresses
  transparently when accessed. Upstream supports runtime compression on
  64-bit Android; unsupported 32-bit terminals are skipped.
- DECRQCRA rectangle checksum replies and XTCHECKSUM calculation selection are
  available through `vt-xt-checksum-report = true` in `termux.properties`.
  The default remains false, as in Ghostty: a program can use single-cell
  checksum queries to read screen contents left by other programs. The
  calculation defaults to DEC semantics and RIS restores that default. The
  option is applied to new sessions, when the Activity reconnects to existing
  sessions, and on `termux-reload-settings`; an active PTY feed defers the
  update until its callbacks finish.

The pin also brings terminal fixes without additional host bindings: Wuffs
zlib decoding for Kitty graphics, clearing stale image-placeholder row flags,
strict OSC numeric parsing and CAN/SUB cancellation, OSC 105 color reset,
palette reset on RIS, live/saved cursor repair during resize, safe truncation
of wide characters, same-size resize handling, paste failure propagation,
and search lifetime checks. Android's existing key and mouse encoders inherit
the Ctrl+Alt+Shift+Backspace and UTF-8 extended-button fixes.

The remaining new C APIs were evaluated against current consumers. OSC 133
prompt markers already update terminal semantic state; the new prompt callback
provides host events, but the app currently has no command-status, completion
notification, or command-history UI to consume them. Adding notifications for
every command would change normal shell behavior, so no such binding is
introduced. Unknown OSC passthrough is an extension hook, with no additional
Termux protocol needing it. Snapshot decode-time compression has no consumer
because this app does not restore native terminal snapshots. Render-state row
identities and overscan are available upstream, but the renderer already
caches shaped text by content and scrolls by whole rows; these APIs do not add
displayed content or avoid current conservative scroll dirty flags.

GTK/EGL/DMABUF, shared desktop render devices, Metal/OpenGL shaders, macOS
windows and clipboard dialogs, tmux control-mode hosting, the desktop SSH
terminfo cache, CoreText and Nerd Font tables, Windows DLL startup, themes,
Nix metadata, translations, and CI changes have no Android host consumer.
The patch retains only custom glyph introspection/width handling, Kitty
animation/virtual-placement access (including its I/O accessor), and the
32-bit page-alignment fix.

Validation for this sync:

- Patched upstream `zig build test-lib-vt -Demit-lib-vt=true -Doptimize=Debug -j4`
  completed successfully. Debug is required for the upstream tests that assert
  slow runtime safety is enabled.
- `:terminal-emulator:testDebugUnitTest`, `:app:testDebugUnitTest`, and
  `:app:assembleDebug` completed successfully: 150 terminal tests and 45 app
  tests passed, including compression queue and checksum property coverage.
- `libghostty-vt` and JNI/Vulkan libraries built for `arm64-v8a`, `armeabi-v7a`,
  `x86`, and `x86_64`. The four ABI APKs and universal debug APK passed archive,
  native-library packaging, and signing verification.
- Device execution was not tested in this sync.

## 2026-09-25 sync

The pin moved from `d4c88d8069912b653d707191388ca98e24751f12` to
`c959af63d11b524a84c21900372990dbc024b059`, the upstream `main` HEAD
queried for this sync. The 94-commit range (including merge commits) was
reviewed by commit list and source diff. The existing Android patch applies
cleanly unchanged; it still supplies glyph inspection, Kitty graphics and
virtual-placement access, and OSC 22 mouse-shape data absent from the upstream
C API. The four-ABI `libghostty-vt` build remains the same.

Upstream terminal fixes for reverse wrap, word selection across wide cells and
hard line breaks, batched DEC special graphics, mode lookup, and Unicode 18
are inherited through the new pin. The new lib-vt render-hold callback for
synchronized output (DEC mode 2026) is connected to the Android Vulkan
renderer: it captures the completed frame when the hold begins, skips live
render-state updates during the hold, and releases it after one second if the
program fails to do so. The view schedules a draw for that deadline, even
when no further PTY output arrives.

The new resize-pull-scrollback switch is meant for Windows ConPTY, which keeps
its own screen without scrollback; Termux uses a POSIX PTY and retains the
upstream default. Render-state overscan is intended for fractional smooth
scrolling; Android currently scrolls by whole cells, so requesting extra rows
would add work without displaying them. CSI 8 t window sizing requires a
desktop window manager and is not bound to Android Activity resize. GTK
fractional scaling, EGL/DMABUF and OpenGL changes, macOS window changes,
shell-integration changes, font cache internals, CI/dependency metadata and
translations have no Android host binding. The terminal-core fixes require no
additional JNI method beyond render hold.

## 2026-09-16 sync

The pin was advanced from `e2e53f861482e080bf45054ba49ef471f9849937` to
`d4c88d8069912b653d707191388ca98e24751f12`, the tip of upstream `main` when
the sync was performed. All 90 commits in the range were reviewed.

The terminal-core changes are directly useful on Android and require no new
host API: libghostty-vt now accepts ANSI DECRQM requests (`CSI Ps $ p`) in
addition to DEC-private requests, and unknown mode requests retain the complete
16-bit parameter instead of aliasing a supported mode. The existing JNI
`write_pty` callback already returns Ghostty's generated replies to the Termux
PTY, so these fixes are enabled by updating the pin. Upstream C API tests cover
the callback path and distinguish ANSI mode 4 from DEC-private mode 4.

The Android patch was rebased against the new source layout. Its exported glyph
introspection, Kitty graphics animation and virtual-placement APIs, OSC 22
mouse-shape query, and renderer support remain Android-specific because these
APIs are still absent from upstream libghostty-vt. No Java or JNI signature
changed, and the existing four-ABI `-Demit-lib-vt=true` build configuration is
still the appropriate upstream build path.

The remaining changes were intentionally not adapted. The GTK renderer moved
away from `GtkGLArea` and added EGL/DMABUF integration, but Android uses its own
Vulkan renderer and does not build the GTK application runtime. OpenGL frame
export and delayed-flush changes belong to that desktop renderer path. The
macOS tab/window and title-bar changes, GTK resource-cache work, Nix updates,
translations, and repository/CI metadata are likewise outside the Android
libghostty-vt architecture. Applying any of those would add platform-specific
dependencies without exposing useful terminal behavior to Termux.

## 2026-09-12 sync

The pin was advanced from `492300cad104195411d12217dd22f1cd05f31376` to
`e2e53f861482e080bf45054ba49ef471f9849937`, the tip of upstream `main` when
the sync was performed.

The 43-commit range contains these changes relevant to this application:

- libghostty-vt now returns C-safe null pointers for empty allocated outputs.
  The existing JNI callers already accept an empty pointer/length pair, so the
  fix is inherited without a new binding.
- POSIX TinyIo and the C API/build integration were refactored. Android uses
  this path and gains the upstream fixes without changing its host contract.
- Compressed terminal pages clear only the written memory before returning
  pooled scratch storage, reducing unnecessary memory work for scrollback.

No new Android-facing feature binding was added for this range. The Kitty
graphics file-medium hardening is Windows-specific (UNC, device namespace,
and reserved device-name rejection); Android already uses Ghostty's POSIX path
validation, so copying the Windows policy would reject valid Android/Termux
paths without adding protection. The macOS display-link deadlock fix, Windows
page-memory reclamation and TinyIo implementation, GTK/macOS build changes,
translations, color-scheme data, and fontconfig update are desktop or unused
by the `libghostty-vt` Android build and are intentionally not adapted.

For future updates, compare the pinned revision with upstream `main`, check the
Android patch with `git apply --check`, then run the native build for every ABI,
the Android unit tests, and an APK build. Update this section when the range
introduces a host-facing terminal API or behavior decision.
