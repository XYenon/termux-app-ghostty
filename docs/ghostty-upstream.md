# Ghostty upstream integration

Ghostty is fetched source code, not a Git submodule or a vendored tree. The
repository and immutable revision are declared in `build.gradle`.
`prepareNativeSources` checks out that revision under `build/native-sources`,
and `buildGhosttyAndroid` clones it into the terminal emulator build directory,
applies `native/patches/ghostty-android.patch`, and builds `libghostty-vt` for
the configured Android ABIs. Keep the patch limited to C APIs needed by the
Android JNI and Vulkan renderer.

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
