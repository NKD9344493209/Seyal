#!/usr/bin/env bash
# End-to-end test on the emulator with a real AI key (GitHub secret GEMINI_API_KEY).
# Uses only fake test contacts. Writes e2e/*.png, runs.json and e2e-report.md.
set -u
PKG=com.seyal.app
RCV="$PKG/.TestReceiver"
HERE="$(cd "$(dirname "$0")" && pwd)"
if [ -z "${GEMINI_API_KEY:-}" ]; then
  echo "No GEMINI_API_KEY secret set: skipping the end-to-end test."
  echo "## End-to-end test" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
  echo "Skipped: add a GEMINI_API_KEY repository secret to run it." >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
  exit 0
fi
mkdir -p e2e
: > timings.tsv
adb logcat -G 16M >/dev/null 2>&1 || true   # keep the whole test in the log buffer

for p in READ_CONTACTS POST_NOTIFICATIONS READ_CALENDAR WRITE_CALENDAR CALL_PHONE RECORD_AUDIO; do
  adb shell pm grant "$PKG" "android.permission.$p" 2>/dev/null || true
done

add_contact() { # name phone email
  adb shell content insert --uri content://com.android.contacts/raw_contacts --bind account_type:n: --bind account_name:n: >/dev/null 2>&1
  local id
  id=$(adb shell content query --uri content://com.android.contacts/raw_contacts --projection _id | grep -o '_id=[0-9]*' | cut -d= -f2 | sort -n | tail -1)
  [ -z "$id" ] && { echo "  could not add contact $1"; return; }
  adb shell "content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$id --bind mimetype:s:vnd.android.cursor.item/name --bind 'data1:s:$1'"
  adb shell "content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$id --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind 'data1:s:$2' --bind data2:i:2"
  [ -n "$3" ] && adb shell "content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$id --bind mimetype:s:vnd.android.cursor.item/email_v2 --bind 'data1:s:$3' --bind data2:i:1"
  echo "  contact: $1"
}
echo "== test contacts"
add_contact "Appa" "+919876500001" "appa.test@example.com"
add_contact "Amma" "+919876500004" ""
add_contact "Ram Kumar" "+919876500002" "ram.kumar@example.com"
add_contact "Ram Project" "+919876500003" "ram.project@example.com"
add_contact "Priya S" "+919876500005" "priya.test@example.com"

echo "== key"
adb shell "am broadcast -n $RCV --es key '$GEMINI_API_KEY' --es name 'Naveen'" >/dev/null
sleep 1
adb shell am force-stop "$PKG"          # fresh start on the Home tab
adb shell am start -W -n "$PKG/.ui.MainActivity" >/dev/null

pull() { adb shell run-as "$PKG" cat files/seyal/runs.json > runs.json 2>/dev/null || echo "[]" > runs.json; }
front() { # bring Seyal back if another app (dialer, mail, calendar) opened
  if ! adb shell dumpsys activity activities 2>/dev/null | grep -E "topResumedActivity|mResumedActivity" | grep -q "$PKG"; then
    adb shell input keyevent KEYCODE_ENDCALL; adb shell input keyevent KEYCODE_BACK; sleep 1
    adb shell am start -n "$PKG/.ui.MainActivity" >/dev/null 2>&1
  fi
}

n=0
while IFS= read -r cmd <&3 || [ -n "$cmd" ]; do
  [ -z "$cmd" ] && continue
  n=$((n + 1)); tag=$(printf "%02d" "$n")
  echo "== [$tag] $cmd"
  pull; before=$(python3 "$HERE/e2e_state.py" count runs.json)
  start=$(date +%s)
  adb shell "am broadcast -n $RCV --es command '$cmd'" >/dev/null
  approved=0; chose=0; state="?"
  for i in $(seq 1 50); do
    sleep 3
    front
    pull
    count=$(python3 "$HERE/e2e_state.py" count runs.json)
    [ "$count" -le "$before" ] && continue          # still planning
    state=$(python3 "$HERE/e2e_state.py" newest runs.json)
    status=${state%% *}
    case "$state" in
      *WAITING_APPROVAL*)
        if [ $approved -lt 4 ]; then
          adb exec-out screencap -p > "e2e/$tag-approval.png"
          adb shell "am broadcast -n $RCV --ez approve_all true" >/dev/null; approved=$((approved + 1)); continue
        fi ;;
      *WAITING_CHOICE*)
        if [ $chose -lt 3 ]; then
          adb exec-out screencap -p > "e2e/$tag-choice.png"
          adb shell "am broadcast -n $RCV --ei choose 0" >/dev/null; chose=$((chose + 1)); continue
        fi ;;
    esac
    case "$status" in DONE|PARTIAL|FAILED|CANCELLED) break ;; esac
    case "$state" in *idle*) [ "$status" = "WAITING" ] && break ;; esac
  done
  secs=$(( $(date +%s) - start ))
  printf "%s\t%s\t%s\n" "$cmd" "$secs" "$state" >> timings.tsv
  echo "   -> $state in ${secs}s"
  front; sleep 2
  adb exec-out screencap -p > "e2e/$tag-result.png"
  sleep 4   # stay under the free-tier rate limit
done 3< "$HERE/e2e-commands.txt"

pull
python3 "$HERE/e2e_state.py" report runs.json timings.tsv > e2e-report.md
{
  echo "## End-to-end test"
  echo '```'
  cut -f1,2,3 timings.tsv
  echo '```'
} >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
adb logcat -d > e2e-logcat.txt
grep "SeyalAI" e2e-logcat.txt > e2e-ai-log.txt || true
if grep -q "FATAL EXCEPTION" e2e-logcat.txt; then
  grep -E "FATAL EXCEPTION" -A 25 e2e-logcat.txt > e2e-crash.txt
  echo "CRASH during end-to-end test"; head -40 e2e-crash.txt
  exit 1
fi
exit 0
