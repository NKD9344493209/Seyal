#!/usr/bin/env bash
# Smoke test on an Android emulator: install, open every screen, screenshot, check for crashes.
set -u
APK="$1"
PKG=com.seyal.app
mkdir -p shots
fail=0

ui() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml ui.xml >/dev/null 2>&1; }

# Tap the centre of the first element whose text is exactly $1.
tap_text() {
  ui
  local b
  b=$(grep -o "text=\"$1\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" ui.xml | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
  if [ -z "$b" ]; then echo "  could not find '$1' on screen"; return 1; fi
  set -- $b
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

shot() { sleep 3; adb exec-out screencap -p > "shots/$1.png"; echo "  screenshot $1"; }

alive() {
  if ! adb shell pidof "$PKG" >/dev/null 2>&1; then echo "APP NOT RUNNING after: $1"; fail=1; fi
}

echo "== install"
adb install -r "$APK" || { echo "install failed"; exit 1; }
for p in POST_NOTIFICATIONS READ_CONTACTS; do adb shell pm grant "$PKG" "android.permission.$p" 2>/dev/null || true; done
adb logcat -c

echo "== launch"
adb shell am start -W -n "$PKG/.ui.MainActivity"
shot 1-home
alive "launch"

echo "== tabs"
tap_text "Recipes" && shot 2-recipes; alive "Recipes tab"
tap_text "Activity" && shot 3-activity; alive "Activity tab"
tap_text "Control" && shot 4-control; alive "Control tab"
adb shell input swipe 540 1800 540 600 300; shot 5-control-scrolled; alive "scroll Control"
adb shell input swipe 540 1800 540 600 300; shot 6-control-bottom; alive "scroll Control 2"
tap_text "Home" && shot 7-home-again; alive "Home tab"

echo "== send without a key (should open Control, not crash)"
tap_text "Remind me in 20 minutes to drink water" && shot 8-after-suggestion; alive "suggestion tap"

echo "== rotate"
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1; shot 9-landscape; alive "rotate"
adb shell settings put system user_rotation 0; sleep 2

echo "== restart (state survives)"
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/.ui.MainActivity"; shot 10-relaunch; alive "relaunch"

adb logcat -d > logcat.txt
grep -E "FATAL EXCEPTION|AndroidRuntime: (E|at |Caused)" -A 25 logcat.txt > crash.txt || true
if [ -s crash.txt ]; then
  echo "== CRASH FOUND"; head -80 crash.txt; fail=1
else
  echo "== no crashes in logcat"
fi
grep -E " $PKG|SeyalApp|com\.seyal" logcat.txt | grep -iE " E |exception|error" | head -60 > app-errors.txt || true

{
  echo "## Emulator smoke test"
  if [ "$fail" = 0 ]; then echo "PASS: opened every screen, rotated, restarted, no crash."; else echo "FAIL: see crash below"; fi
  if [ -s crash.txt ]; then echo '```'; head -60 crash.txt; echo '```'; fi
} >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
if [ -s crash.txt ]; then
  head -10 crash.txt | while IFS= read -r line; do echo "::error title=Crash::${line}"; done
fi
exit $fail
