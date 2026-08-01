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

ndk_path="$ANDROID_SDK_ROOT/ndk/$ANDROID_NDK_VERSION"
cmake_path="$ANDROID_SDK_ROOT/cmake/$ANDROID_CMAKE_VERSION"

publish_ndk_environment() {
    if [[ -n "${GITHUB_ENV:-}" ]]; then
        {
            printf 'ANDROID_NDK=%s\n' "$ndk_path"
            printf 'ANDROID_NDK_HOME=%s\n' "$ndk_path"
            printf 'ANDROID_NDK_ROOT=%s\n' "$ndk_path"
        } >> "$GITHUB_ENV"
    fi
}

if [[ -d "$ndk_path/toolchains/llvm/prebuilt" ]] &&
    [[ -x "$cmake_path/bin/cmake" ]]; then
    publish_ndk_environment
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

sdkmanager_java_home="${SDKMANAGER_JAVA_HOME:-${JAVA_HOME_17_X64:-${JAVA_HOME_17_ARM64:-${JAVA_HOME_17:-${JAVA_HOME:-}}}}}"

run_sdkmanager() {
    if [[ -n "$sdkmanager_java_home" ]]; then
        JAVA_HOME="$sdkmanager_java_home" \
            PATH="$sdkmanager_java_home/bin:$PATH" \
            "$sdkmanager" "$@"
    else
        "$sdkmanager" "$@"
    fi
}

yes 2>/dev/null | run_sdkmanager --licenses >/dev/null 2>&1 || true
run_sdkmanager "ndk;$ANDROID_NDK_VERSION" "cmake;$ANDROID_CMAKE_VERSION"
publish_ndk_environment
