# Ghostty upstream integration

Ghostty is fetched source code, not a Git submodule or a vendored tree. The
repository and immutable revision are declared in `build.gradle`.
`prepareNativeSources` checks out that revision under `build/native-sources`,
and `buildGhosttyAndroid` clones it into the terminal emulator build directory,
applies `native/patches/ghostty-android.patch`, and builds `libghostty-vt` for
the configured Android ABIs. Keep the patch limited to C APIs needed by the
Android JNI and Vulkan renderer.

## 2026-10-27 sync

The immutable pin moved from `22fa134a07f5749edd9e92c6bc17d8aaf61843be` to
`4a4fc017940bec38c93b278c10c6a18c591f3e63` (1.4.1-dev), the upstream `main`
HEAD queried at the start of this sync. The range contains 212 commits,
including merges, and a 478-file net diff (24,935 insertions, 5,883 deletions).
The commit range and source changes were reviewed, separating the Zig 0.17
mechanical migration from terminal behavior, C API contracts, shell scripts,
snapshot serialization and desktop-runtime changes
([upstream comparison](https://github.com/ghostty-org/ghostty/compare/22fa134a07f5749edd9e92c6bc17d8aaf61843be...4a4fc017940bec38c93b278c10c6a18c591f3e63)).

Android adaptations:

- The native build, Gradle version check, CI, JitPack and orb Zig installation
  now require Zig 0.17.0. Its optimize option is `fast` instead of `ReleaseFast`.
  The upstream Android NDK package supplies the expanded libc configuration
  required by Zig 0.17. Android API 29, NDK 29 and the four ABIs are retained.
- Official `ghostty_kitty_graphics_animation_tick` replaces the custom patch
  export. JNI obtains the active screen's image-storage handle under the
  existing native lock and converts the upstream absolute next-frame deadline
  into the delay expected by the Java scheduler. Stopped or absent animations
  still return no scheduled wakeup.
- The Vulkan renderer now reads every fixed, Unicode-placeholder and relative
  Kitty placement through the official RenderState image iterator. This keeps
  placement geometry and text rows in the same captured state, including
  virtual-parent chains, source rectangles, offsets, negative viewport rows
  and the three composition layers. It sorts by layer, z and image ID and
  caches the list until the placement generation or dimensions change.
  Placement-only changes force repainting even when text cells stay clean.
  The redundant custom virtual-placement API and animation patch are removed.
- The smaller Android patch retains glyph introspection, contour/point access,
  registration/reset generation and cell-width overrides, the terminal I/O
  accessor, and the explicit `usize` cast in the 32-bit page-alignment check.
  Its enum conversions use Zig 0.17's backing-integer builtins.
- Existing OSC 7501 support replies, inherited app resolution, session-owned
  records, ordered events outside the native lock, UI, subtree clear, bounded
  storage, prompt/exit cleanup, dismissal and RIS/reset behavior remain in
  place. Upstream now rejects malformed IDs before invoking the callback;
  an invalid ID cannot accidentally update the root record.
- Updated Bash/Zsh resources remove control bytes from reported hostnames and
  working directories; Zsh also prints directory backslashes literally. This
  prevents directory names from injecting OSC commands. Bash, Zsh, Fish,
  Nushell and Elvish resources still install under `$PREFIX/share/ghostty`.
  Bash/Zsh use their existing system rc files, Fish its native conf.d, and Nu
  its vendor autoload path. No HOME files or invented rc-directory mechanism
  are introduced. Elvish still requires `use ghostty-integration`, as it has
  no global autoload entry point. Prompt navigation and custom ZDOTDIR work
  with the new library; existing unmarked history cannot gain prompt marks.

The pin also brings left-margin bounds for CUB/BS, origin-relative
CHA/VPA/HPR/VPR, VT formatter preservation of erased backgrounds and trailing
blank rows, discarded cancelled/aborted APC commands, C1 continuation restart,
stable Kitty transmissions and pending animation frames, removal of image
placements from erased history, garbage tracking for erased pins, bounded page
row calculations and cursor style/hyperlink lifetime after history pruning.
These run in the existing lib-vt parser, formatter and render consumers.

Reviewed changes that need no new Android integration:

- `vt_write_until` replaces `vt_write_until_ground` and can stop at APC/DCS/OSC
  boundaries. Termux consumes complete PTY chunks with `vt_write`; it has no
  proxy/interceptor consumer of the removed entry point. Adding a second parse
  path would provide no current app behavior.
- Binary snapshots can now carry Kitty storage, fixed/virtual/relative
  placements, animation frames, loading state and raw pixel payloads. Optional
  unknown records are skipped with CRC validation. These library capabilities
  are inherited and tested upstream; Termux currently has no binary-snapshot
  persistence consumer or UI to connect. No app persistence feature is added.
- GTK's new OSC 5522 multi-MIME clipboard handling, consent previews and grants
  belong to its GDK/GTK runtime. Android already implements that C callback
  contract with its own MIME/content-provider handling, permission UI, session
  grants and limits. Copying GTK code would duplicate that integration.
- Kitty POSIX shared-memory name normalization remains unavailable on Android,
  where the existing transport policy deliberately disables shared memory.
  Direct/file transports and their existing limits remain in place.
- GTK split minimums/ratios/focus, IME positioning, progress animation and
  OpenURI activation tokens, macOS AppleScript title overrides/tab movement/
  split zoom/pasteboard tests, desktop initialization cleanup and termio
  mailbox changes have no lib-vt/Android runtime consumer. Termux uses its own
  session, PTY, input and Vulkan ownership. FreeBSD memory/IO fixes, Linux
  launcher paths, pkg-config distribution, Nix/Apple SDK/desktop build changes,
  benchmarks, CI, themes, translations and contributor metadata need no host
  adaptation. Tmux layout validation is inherited by the library but does not
  introduce a new Termux control-mode UI.

Validation:

- A forced Gradle run of `prepareNativeSources`, `buildGhosttyAndroid`,
  `buildGhosttyTerminfo`, `test`, `assembleDebug` and `lint` completed with
  `BUILD SUCCESSFUL` (217 executed tasks). Native/JNI compilation covered
  arm64-v8a, armeabi-v7a, x86 and x86_64. The 420 debug/release Java test
  executions had zero failures, errors or skips; all four modules reported
  zero lint issues.
- The patched upstream `zig build test-lib-vt -Demit-lib-vt=true
  -Doptimize=debug -j2 --summary all` passed all 45 build steps: 6,943 tests
  passed, 74 skipped and zero failed. An initial run under `/tmp` failed the
  same temporary-file prefix assertion in both suites because the fixture's
  outside directory was itself inside the deliberately allowed `/tmp` root.
  Moving only `.zig-cache/tmp` to a directory outside `/tmp` made the complete
  unchanged suite pass; no source/test changes or ignored failures were used.
  Forward/reverse patch checks and `git diff --check` also passed.
- A host fixture linked the patched library and exercised the Android
  renderer's actual placement collection and pixel composition functions:
  fixed/virtual/relative placements, virtual parents, asymmetric source crops,
  negative viewport clipping, scroll/no-op generations, erased history,
  animation deadlines/pixels/stop, glyph contours/constraints/two-cell widths/
  reset, malformed OSC 7501 IDs and RIS passed. The inspected fixture images
  are host CPU output, not captures from an Android Vulkan device.
- Real PTY sessions with Bash, Zsh, Fish, Nushell and Elvish generated OSC 133
  marks and moved the library viewport backwards and forwards between prompts.
  Custom ZDOTDIR passed, all user rc bytes stayed unchanged, and an
  unintegrated Zsh control produced no marks or navigation movement.
- All four ABI APKs and the universal APK passed ZIP integrity, exact ABI and
  native-library membership, ELF/JNI/glyph/official Kitty symbol checks,
  16 KiB LOAD alignment, pinned terminfo and nine shell-resource/loader byte
  comparisons, and APK v2 signature verification. `infocmp` read the generated
  xterm-ghostty entry. The arm64 APK is 45,462,279 bytes with SHA-256
  `6ccf41b64217ad498a3861f4584fed4bc944749a6ec3664428575894c57f7fe4`.
- No Android device was connected. Device PTY/JNI execution, actual Vulkan
  presentation and touch/keyboard interactions remain unverified on hardware.

## 2026-10-16 sync

The pin moved from `7ec9e26a29399b30ef78f06985fc75045b3cecb6` to
`22fa134a07f5749edd9e92c6bc17d8aaf61843be`, the upstream `main` HEAD queried
at the start of this sync (October 15 UTC). All 148 commits, including merges,
and the complete 206-file net diff were reviewed
([upstream comparison](https://github.com/ghostty-org/ghostty/compare/7ec9e26a29399b30ef78f06985fc75045b3cecb6...22fa134a07f5749edd9e92c6bc17d8aaf61843be)).
The immutable fetch and four-ABI Zig 0.16.0 / Android API 29 build remain
compatible. The Android patch applies unchanged: custom glyph introspection,
generation and width handling, Kitty animation, virtual and relative placement
geometry, the animation I/O accessor, and the 32-bit page-alignment fix are
retained. JNI and the Vulkan renderer continue to build against these APIs.

Android integration changes:

- The new `GHOSTTY_SCROLL_VIEWPORT_DELTA_PROMPT` operation is exposed through
  JNI and the terminal view. `Previous prompt` / `Next prompt` in the context
  menu and `Ctrl+Alt+PageUp` / `Ctrl+Alt+PageDown` navigate OSC 133 prompt
  groups. Navigation stops a pending fling and clears text selection. It sends
  no PTY input; upstream handles continuation prompts, history limits, shells
  without markers, and alternate-screen behavior. The existing setting that
  disables Ctrl+Alt shortcuts also disables these shortcuts.
- The pinned Bash, Zsh, Fish, Elvish and Nushell integration sources are now
  bundled and installed atomically beneath `$PREFIX/share/ghostty` on startup
  and after bootstrap. Android excludes dotfiles from assets, so `.zshenv`
  travels as `zshenv` and is restored on install. Bash/Zsh directly source
  their upstream scripts through an idempotent block appended to the existing
  system `etc/bash.bashrc` / `etc/zshrc`; existing bytes are preserved.
  No custom rc-directory scanning mechanism is introduced.
  Missing package conffiles are not created before their shell is installed.
  Fish loads through `etc/fish/conf.d`. Nushell uses a vendor autoload loader
  beneath `share/nushell`, retaining its native OSC 133 settings. Elvish's
  module is discoverable beneath `share/elvish/lib`; as upstream requires,
  `use ghostty-integration` activates it. Elvish has no system rc/autoload
  entry point, so fully automatic activation would require changing user rc
  handling; no HOME files are changed or generated. Bash/Zsh/Fish startup
  order and user rc/ZDOTDIR are preserved. Non-failsafe shell environments
  expose the resource directory and prepend the Termux system share directory
  to XDG_DATA_DIRS while retaining existing entries. Desktop CLI wrappers,
  title and cursor features remain disabled. This fixes the reproduced case
  where an unintegrated zsh produces no OSC 133 marks and navigation cannot
  move. A new session is needed; old unmarked history cannot be reconstructed.
- OSC 7501 revision 0.4 support replies now list supported states and kinds,
  using the existing PTY callback and preserving BEL or ST. A report with an
  empty `app` inherits the nearest retained ancestor's nonempty app, including
  the root record. Inheritance is resolved for each new immutable snapshot,
  so late parents, replacement, removal and eviction are reflected without
  changing raw reports or earlier snapshots. Whole-record replacement,
  segment-aware subtree clears, the 64-record limit, ordered delivery outside
  the native lock, prompt/exit cleanup, finished-result dismissal, RIS and
  explicit reset behavior remain intact. Actual sidebar and detail UI consume
  these resolved records.

The pin directly brings DECXCPR (`CSI ? 6 n`), terminal-size replies (`CSI 19 t`),
DECSTR soft reset, default tab-clear and permanently-reset ANSI mode reporting
fixes. It also fixes grapheme widening across page capacity, cursor lifetime
when pruning single-row screens, ED2 at blank prompt boundaries, pending wrap
with DECAWM disabled, wide-character selection/render bounds, VT formatting of
OSC 8 links and blank wrapped lines, bounded Kitty clipboard alias retention,
and snapshot page-capacity validation. Existing C API, PTY reply, selection and
render-state consumers inherit these changes without duplicate host code.

Changes evaluated but not adapted:

- CSI 8 t remains unbound: the Android Activity determines the terminal grid;
  terminal output cannot resize an Android desktop window. CSI 19 t uses the
  existing size-report callback instead.
- OSC 3008's renamed hierarchical-context parser and lazy field unescaping
  do not yet have a terminal action or C host UI contract. There is no existing
  Android context consumer to connect.
- The new desktop surface paste entry point and macOS drag/drop, executable
  file and URL policies belong to the full desktop runtime. Android already
  uses the lib-vt paste encoder and its own link/clipboard UI. The upstream
  unsafe-paste check now recognizes CR as well as LF, but Android intentionally
  retains its existing `allow_unsafe = true` paste behavior; this sync does not
  introduce a new paste confirmation policy.
- GTK 4.18 / libadwaita 1.7 / Blueprint 0.20 minimums, EGL display sharing,
  desktop notification timers and focus cleanup, tab inversion and signals,
  macOS display-link ownership and split menus, CLI theme paging, desktop font
  discovery, Windows TinyIo, build/CI dependencies and translations have no
  Android lib-vt/Vulkan consumer. The desktop PTY resize locking change also
  needs no copy: Termux serializes PTY parsing and grid updates on its main
  handler. The shared Zig path-constant migration is inherited by the build.

Validation for this sync:

- `prepareNativeSources`, `:terminal-emulator:buildGhosttyAndroid` and
  `:app:buildGhosttyTerminfo` passed. The Ghostty native build covers
  `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64`.
- On the patched source, `zig build test-lib-vt -Demit-lib-vt=true
  -Doptimize=Debug -j2 --summary all` passed: 45/45 steps, 6,710 tests passed,
  72 skipped, no failures.
- `./gradlew --no-daemon test assembleDebug lint` passed (216 tasks).
  After removing the temporary menu-render validation fixture, `test` passed
  again: debug and release each have 157 terminal tests and 47 app tests, with
  no failures or skips. All four modules and both variants have zero lint
  issues. No build-configuration or Android-patch workaround was necessary.
- A separate host build of the patched lib-vt passed a C ABI smoke check:
  exact OSC 7501 capability replies with both terminators, CSI 19 t and
  DECXCPR replies, prompt offsets in both directions and history clamping,
  glyph registration/introspection, two-cell layout and reset generation,
  plus the Kitty animation/virtual-placement APIs with no images present.
- Robolectric native graphics rendered the real context menu in English and
  Simplified Chinese at 2x; labels and action dispatch passed. Actual sidebar
  adapter and dialog tests cover inherited app display, client replacement,
  prompt cleanup, retained completion and explicit dismissal. Default,
  working, blocked, done and detail screenshots were visually inspected.
- All four ABI APKs and the universal APK passed ZIP CRC checks, exact
  ABI/library-set checks, ELF class/machine checks, exported JNI and custom
  glyph/Kitty symbol checks, byte-for-byte bundled terminfo checks against
  generated assets, and `apksigner verify` (v2, existing debug certificate).
  `infocmp` reads the generated `xterm-ghostty` entry successfully.
- `git diff --check` passed. Existing SDK XML, target-SDK, NDK ABI metadata,
  HarfBuzz CMake and older `tic` notices remain toolchain warnings. No Android
  device is connected, so real-device JNI/PTY/Vulkan behavior and a native
  pointer/keyboard interaction recording were not exercised.

Shell integration follow-up validation used real Bash 5.2, Zsh 5.9, Fish 3.6,
Nushell 0.116.1 and Elvish 0.19.2 in PTYs, feeding their output live into the
patched host lib-vt (including PTY query replies). Unintegrated Zsh produced
zero OSC 133 marks and viewport offsets `16 -> 16 -> 16 -> 16`. With system
loaders, previous/previous/next moved Zsh `16 -> 15 -> 12 -> 15`, Bash/Fish/Nu
`14 -> 12 -> 9 -> 12`, and Elvish after its explicit import
`15 -> 13 -> 10 -> 13`. A custom ZDOTDIR passed the same Zsh navigation and
startup checks. User rc bytes were unchanged and no integration files appeared
under HOME. Robolectric checks cover all bundled resources, repair, system rc
preservation, idempotent loading and absent-package behavior. These host checks
do not substitute for a connected Android device.

## 2026-10-09 sync

The pin moved from `c3203ea4b169a18eb2ccfe92847e426d8afea858` to
`7ec9e26a29399b30ef78f06985fc75045b3cecb6`, the upstream `main` HEAD
queried for this sync. All 18 commits, including merge commits, and the
15-file source diff were reviewed ([upstream comparison](https://github.com/ghostty-org/ghostty/compare/c3203ea4b169a18eb2ccfe92847e426d8afea858...7ec9e26a29399b30ef78f06985fc75045b3cecb6)).
The Android patch applies unchanged and retains the existing glyph, Kitty
graphics, and 32-bit page-alignment extensions.

OSC 7501 program status is integrated through upstream's
`GHOSTTY_TERMINAL_OPT_PROGRAM_STATUS` callback, with no new patch API:

- Upstream validates reports and decodes their UTF-8 title/message. Installing
  the callback enables support-query replies through the existing PTY writer,
  preserving BEL or ST; reports themselves are never echoed.
- JNI copies borrowed strings and delivers every report, clear, and OSC 133
  prompt event in stream order after releasing the native mutex. This preserves
  reports from multiple tasks, clears followed by updates, and resets followed
  by new reports in one PTY read.
- Each `TerminalSession` retains up to 64 records independently of its Activity
  client. Reports replace entire records, hierarchy clears match ID path
  segments, and excess records evict the least recently updated entry.
- A new OSC 133 prompt or process exit removes idle, working, and blocked
  records. Done/error records remain until the user explicitly dismisses them.
  RIS and the app's explicit terminal reset clear all records.
- The session sidebar shows a two-line summary, newest reports first. Tapping
  it opens all task details, including blocked reasons, progress, app, ID, title,
  and message. The dialog identifies the originating session and can dismiss
  finished results. Text is plain text with invisible formatting characters
  removed. English and Simplified Chinese labels are provided.

The existing OSC 9;4 progress bar remains a separate protocol; collapsing
7501's multiple records into that single transient percentage would lose
task identities and blocked/done semantics. Status reports do not generate
automatic Android notifications, so frequent updates do not interrupt the
user or add a notification permission requirement.

The pin also inherits terminal fixes for Unicode above U+00FF under legacy
charsets, SS2/SS3 consumption by combining characters, preserving a character's
mapping when VS16 widens it across a wrap, line selection across unwritten cells
at semantic prompt boundaries, and origin-mode cursor coordinates in VT
serialization. These need no JNI or Vulkan changes. The Danish desktop
translations and contributor-vouch metadata have no Android consumer.

Validation for this sync:

- Patched upstream `zig build test-lib-vt -Demit-lib-vt=true -Doptimize=Debug -j4`
  completed successfully, including upstream OSC 7501 parsing, support-query,
  C callback, split-input, and RIS coverage.
- `./gradlew --no-daemon prepareNativeSources :terminal-emulator:buildGhosttyAndroid
  :app:buildGhosttyTerminfo test assembleDebug lint` completed successfully
  (216 tasks). Debug and release each passed 155 terminal tests and 46 app
  tests; all modules and both variants reported zero lint issues.
- New store tests distinguish whole-record replacement, subtree clears from
  prefix collisions, transient from finished states, update-order eviction,
  and formatting-control removal. Robolectric native graphics exercised the
  actual sidebar adapter and dialog, client replacement, prompt cleanup,
  explicit dismissal, and recycled rows. Default, working, blocked, done,
  and full-detail renders were captured at 2x and visually inspected.
- The app test worker now has a 1 GiB heap: the combined graphics and existing
  64 MiB clipboard boundary tests exhausted Gradle's default 512 MiB heap.
  No boundary tests were skipped or reduced. Existing SDK XML, target-SDK,
  NDK ABI metadata, and older `tic` notices remain toolchain warnings.
- `libghostty-vt` and JNI/Vulkan libraries built for `arm64-v8a`, `armeabi-v7a`,
  `x86`, and `x86_64`. All four ABI APKs and the universal debug APK passed ZIP,
  exact native ABI/library, bundled terminfo, and `apksigner verify` checks.
  Java bytecode descriptors match the two new JNI callback registrations.
- `git diff --check` passed. No Android device was connected, so JNI/PTY and
  Vulkan behavior on a real device were not executed in this sync.

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
