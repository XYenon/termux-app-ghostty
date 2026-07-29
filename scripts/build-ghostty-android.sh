#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

NATIVE_SOURCES_DIR="${NATIVE_SOURCES_DIR:-$ROOT_DIR/build/native-sources}"
GHOSTTY_BASE_SRC="$NATIVE_SOURCES_DIR/ghostty"
GHOSTTY_SRC="${GHOSTTY_PATCHED_SRC:-$ROOT_DIR/terminal-emulator/build/ghostty-source}"
ZIG_VERSION="${ZIG_VERSION:?}"
ZIG="${ZIG:-$(command -v zig || command -v python-zig || true)}"
ANDROID_NDK_VERSION="${ANDROID_NDK_VERSION:?}"
ANDROID_API_LEVEL="${ANDROID_API_LEVEL:?}"
DEFAULT_ANDROID_ABIS="${DEFAULT_ANDROID_ABIS:-arm64-v8a,armeabi-v7a,x86,x86_64}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}/ndk/$ANDROID_NDK_VERSION}"
PATCH_FILE="$ROOT_DIR/native/patches/ghostty-android.patch"

"$ROOT_DIR/scripts/verify-native-deps.sh"

if [[ ! -d "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt" ]]; then
    printf 'Android NDK %s not found at %s\n' \
        "$ANDROID_NDK_VERSION" "$ANDROID_NDK_HOME" >&2
    exit 1
fi

rm -rf "$GHOSTTY_SRC"
git clone --quiet --no-hardlinks "$GHOSTTY_BASE_SRC" "$GHOSTTY_SRC"
git -C "$GHOSTTY_SRC" checkout -q --detach "$GHOSTTY_REVISION"
if git -C "$GHOSTTY_SRC" apply --check "$PATCH_FILE"; then
    git -C "$GHOSTTY_SRC" apply "$PATCH_FILE"
else
    printf 'Ghostty Android patch does not apply cleanly to %s\n' \
        "$GHOSTTY_SRC" >&2
    exit 1
fi

IFS=',' read -r -a requested_abis <<< "${TERMUX_ABIS:-$DEFAULT_ANDROID_ABIS}"
build_jobs="${GHOSTTY_BUILD_JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || nproc)}"

for android_abi in "${requested_abis[@]}"; do
    case "$android_abi" in
        arm64-v8a) zig_target="aarch64-linux-android" ;;
        armeabi-v7a) zig_target="arm-linux-androideabi" ;;
        x86) zig_target="x86-linux-android" ;;
        x86_64) zig_target="x86_64-linux-android" ;;
        *)
            printf 'Unsupported Android ABI: %s\n' "$android_abi" >&2
            exit 1
            ;;
    esac

    output_dir="${GHOSTTY_ANDROID_OUTPUT_ROOT:-$ROOT_DIR/terminal-emulator/build/ghostty}/$android_abi"
    mkdir -p "$output_dir"
    (
        cd "$GHOSTTY_SRC"
        ANDROID_NDK_HOME="$ANDROID_NDK_HOME" "$ZIG" build \
            --prefix "$output_dir" \
            -Demit-lib-vt=true \
            -Dtarget="$zig_target.$ANDROID_API_LEVEL" \
            -Doptimize=ReleaseFast \
            -Dstrip=true \
            -j"$build_jobs"
    )

    test -f "$output_dir/lib/libghostty-vt.a"
    test -f "$output_dir/include/ghostty/vt.h"
done
