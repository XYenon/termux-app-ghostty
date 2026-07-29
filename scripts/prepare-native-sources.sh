#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

OUTPUT_DIR="${NATIVE_SOURCES_DIR:-$ROOT_DIR/build/native-sources}"
REVISION_DIR="$OUTPUT_DIR/.revisions"

checkout_source() {
    local name="$1"
    local repository="$2"
    local commit="$3"
    local directory="$4"
    local path="$OUTPUT_DIR/$directory"

    if [[ -d "$path/.git" ]] &&
        [[ "$(git -C "$path" rev-parse HEAD)" == "$commit" ]]; then
        printf '%s\n' "$commit" > "$REVISION_DIR/$directory"
        return
    fi

    rm -rf "$path"
    mkdir -p "$path"
    git init -q "$path"
    git -C "$path" remote add origin "$repository"
    git -C "$path" fetch --depth=1 origin "$commit"
    git -C "$path" checkout -q --detach FETCH_HEAD
    printf '%s\n' "$commit" > "$REVISION_DIR/$directory"
    printf 'Prepared %s at %s\n' "$name" "$commit"
}

mkdir -p "$OUTPUT_DIR" "$REVISION_DIR"
checkout_source Ghostty "${GHOSTTY_REPOSITORY:?}" "${GHOSTTY_REVISION:?}" ghostty
checkout_source FreeType "${FREETYPE_REPOSITORY:?}" "${FREETYPE_REVISION:?}" freetype
checkout_source HarfBuzz "${HARFBUZZ_REPOSITORY:?}" "${HARFBUZZ_REVISION:?}" harfbuzz
checkout_source libpng "${LIBPNG_REPOSITORY:?}" "${LIBPNG_REVISION:?}" libpng
