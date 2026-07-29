#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

NATIVE_SOURCES_DIR="${NATIVE_SOURCES_DIR:-$ROOT_DIR/build/native-sources}"
GHOSTTY_SRC="$NATIVE_SOURCES_DIR/ghostty"
GHOSTTY_REVISION="${GHOSTTY_REVISION:?}"
ZIG_VERSION="${ZIG_VERSION:?}"
ZIG="${ZIG:-$(command -v zig || command -v python-zig || true)}"
OUTPUT_DIR="${GHOSTTY_TERMINFO_OUTPUT_DIR:-$ROOT_DIR/app/build/generated/ghosttyTerminfo/assets/ghostty}"
SOURCE_FILE="$OUTPUT_DIR/ghostty.terminfo"
DATABASE_DIR="$OUTPUT_DIR/terminfo"

if [[ "$(git -C "$GHOSTTY_SRC" rev-parse HEAD)" != "$GHOSTTY_REVISION" ]]; then
    printf 'Ghostty checkout does not match %s\n' "$GHOSTTY_REVISION" >&2
    exit 1
fi
if [[ ! -x "$ZIG" ]] || [[ "$("$ZIG" version)" != "$ZIG_VERSION" ]]; then
    printf 'Zig %s is required at %s\n' "$ZIG_VERSION" "$ZIG" >&2
    exit 1
fi

command -v tic >/dev/null || {
    printf 'The ncurses tic command is required to build Ghostty terminfo\n' >&2
    exit 1
}

mkdir -p "$OUTPUT_DIR"
rm -rf "$DATABASE_DIR"

"$ZIG" run \
    --dep ghostty_terminfo \
    "-Mroot=$ROOT_DIR/scripts/generate-ghostty-terminfo.zig" \
    "-Mghostty_terminfo=$GHOSTTY_SRC/src/terminfo/main.zig" \
    > "$SOURCE_FILE"

tic -x -o "$DATABASE_DIR" "$SOURCE_FILE"
find "$DATABASE_DIR" -type f ! -path "$DATABASE_DIR/x/xterm-ghostty" -delete
find "$DATABASE_DIR" -type l -delete
find "$DATABASE_DIR" -depth -type d -empty -delete

test -s "$SOURCE_FILE"
test -s "$DATABASE_DIR/x/xterm-ghostty"
