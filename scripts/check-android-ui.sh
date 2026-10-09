#!/usr/bin/env bash
set -uo pipefail

# Export screenshots while the emulator is still alive, even when an assertion fails.
ui_test_status=0
./gradlew connectedDebugAndroidTest || ui_test_status=$?
mkdir -p ui-screenshots
if ! adb pull /sdcard/Pictures/lyrics-ui ui-screenshots; then
    if [[ "$ui_test_status" -eq 0 ]]; then ui_test_status=1; fi
fi
exit "$ui_test_status"
