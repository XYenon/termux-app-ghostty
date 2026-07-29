#!/usr/bin/env bash

set -euo pipefail

if [[ "${CI:-}" != "true" ]]; then
    printf 'This script is intended for CI build-tool setup only.\n' >&2
    exit 1
fi

ZIG_VERSION="${ZIG_VERSION:?}"
ANDROID_NDK_VERSION="${ANDROID_NDK_VERSION:?}"
ANDROID_CMAKE_VERSION="${ANDROID_CMAKE_VERSION:?}"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"

zig="${ZIG:-$(command -v zig || command -v python-zig || true)}"
if [[ -z "$zig" ]] || [[ "$("$zig" version)" != "$ZIG_VERSION" ]]; then
    printf 'Zig %s must be installed by the workflow.\n' "$ZIG_VERSION" >&2
    exit 1
fi

ndk_path="${ANDROID_NDK_HOME:-$ANDROID_SDK_ROOT/ndk/$ANDROID_NDK_VERSION}"
cmake_path="$ANDROID_SDK_ROOT/cmake/$ANDROID_CMAKE_VERSION"
if [[ -d "$ndk_path/toolchains/llvm/prebuilt" ]] &&
    [[ -x "$cmake_path/bin/cmake" ]]; then
    exit 0
fi

sdkmanager=""
for candidate in \
    "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" \
    "$ANDROID_SDK_ROOT/cmdline-tools/bin/sdkmanager" \
    "$ANDROID_SDK_ROOT/tools/bin/sdkmanager"; do
    if [[ -x "$candidate" ]]; then
        sdkmanager="$candidate"
        break
    fi
done
if [[ -z "$sdkmanager" ]]; then
    printf 'sdkmanager not found under %s\n' "$ANDROID_SDK_ROOT" >&2
    exit 1
fi

yes | "$sdkmanager" --licenses >/dev/null || true
"$sdkmanager" "ndk;$ANDROID_NDK_VERSION" "cmake;$ANDROID_CMAKE_VERSION"
