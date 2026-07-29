#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

NATIVE_SOURCES_DIR="${NATIVE_SOURCES_DIR:-$ROOT_DIR/build/native-sources}"
GHOSTTY_SRC="$NATIVE_SOURCES_DIR/ghostty"
FREETYPE_SRC="$NATIVE_SOURCES_DIR/freetype"
HARFBUZZ_SRC="$NATIVE_SOURCES_DIR/harfbuzz"
LIBPNG_SRC="$NATIVE_SOURCES_DIR/libpng"
GHOSTTY_REVISION="${GHOSTTY_REVISION:?}"
FREETYPE_REVISION="${FREETYPE_REVISION:?}"
HARFBUZZ_REVISION="${HARFBUZZ_REVISION:?}"
LIBPNG_REVISION="${LIBPNG_REVISION:?}"
ZIG_VERSION="${ZIG_VERSION:?}"
ZIG="${ZIG:-$(command -v zig || command -v python-zig || true)}"

verify_checkout() {
    local name="$1"
    local path="$2"
    local expected="$3"

    if [[ ! -d "$path/.git" ]]; then
        printf '%s checkout not found at %s\n' "$name" "$path" >&2
        return 1
    fi

    local actual
    actual="$(git -C "$path" rev-parse HEAD)"
    if [[ "$actual" != "$expected" ]]; then
        printf '%s is at %s, expected %s\n' "$name" "$actual" "$expected" >&2
        return 1
    fi
}

verify_checkout Ghostty "$GHOSTTY_SRC" "$GHOSTTY_REVISION"
verify_checkout FreeType "$FREETYPE_SRC" "$FREETYPE_REVISION"
verify_checkout HarfBuzz "$HARFBUZZ_SRC" "$HARFBUZZ_REVISION"
verify_checkout libpng "$LIBPNG_SRC" "$LIBPNG_REVISION"

if [[ ! -x "$ZIG" ]]; then
    printf 'Zig %s not found at %s\n' "$ZIG_VERSION" "$ZIG" >&2
    exit 1
fi

actual_zig="$("$ZIG" version)"
if [[ "$actual_zig" != "$ZIG_VERSION" ]]; then
    printf 'Zig is %s, expected %s\n' "$actual_zig" "$ZIG_VERSION" >&2
    exit 1
fi

printf 'Native dependency check passed.\n'
