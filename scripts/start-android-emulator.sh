#!/usr/bin/env bash

set -euo pipefail

android_sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/.local/share/android-sdk}}"
emulator="$android_sdk_root/emulator/emulator"

# The emulator does not reliably start the adb server in headless orbs.
"$android_sdk_root/platform-tools/adb" start-server >/dev/null

if [[ -r /dev/kvm && -w /dev/kvm ]]; then
    acceleration="on"
else
    acceleration="off"
fi

exec "$emulator" \
    -avd termux-ghostty-api30 \
    -no-window \
    -no-audio \
    -no-boot-anim \
    -gpu swiftshader_indirect \
    -accel "$acceleration" \
    -no-snapshot
