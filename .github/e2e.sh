#!/usr/bin/env bash
# End-to-end test on the emulator with a real AI key (GitHub secret GEMINI_API_KEY).
# Uses only fake test contacts. Drives real apps through the screen when the Full edition is installed.
# Writes e2e/*.png, index.json, sessions/, e2e-report.md.
set -u
PKG=com.seyal.app
RCV="$PKG/.TestReceiver"
HERE="$(cd "$(dirname "$0")" && pwd)"
FULL="${1:-lite}"
if [ -z "${GEMINI_API_KEY:-}" ]; then
  echo "No GEMINI_API_KEY secret set: skipping the end-to-end test."
  echo "## End-to-end test" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
  echo "Skipped: add a GEMINI_API_KEY repository secret to run it." >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
  exit 0
fi
mkdir -p e2e sessions
: > timings.tsv
rm -f ui-stale.tsv
adb logcat -G 16M >/dev/null 2>&1 || true
# Keep the screen on for the whole test.
adb shell svc power stayon true
adb shell settings put system screen_off_timeout 1800000
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard 2>/dev/null || true
shot() { adb shell input keyevent KEYCODE_WAKEUP; adb exec-out screencap -p > "$1"; }

for p in READ_CONTACTS POST_NOTIFICATIONS READ_CALENDAR WRITE_CALENDAR CALL_PHONE RECORD_AUDIO SEND_SMS ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION; do
  adb shell pm grant "$PKG" "android.permission.$p" 2>/dev/null || true
done
adb shell appops set "$PKG" GET_USAGE_STATS allow 2>/dev/null || true

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
adb shell "am broadcast -n $RCV --es key '$GEMINI_API_KEY' --es name 'Naveen' --es city 'Coimbatore'" >/dev/null
sleep 1
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/.ui.MainActivity" >/dev/null
sleep 2

# Screen control has to be switched on after the force-stop above (a force-stop turns it off).
if [ "$FULL" = "full" ]; then
  echo "== screen control on (accessibility), notification access, special access"
  adb shell settings put secure enabled_accessibility_services "$PKG/$PKG.device.SeyalAccessibilityService"
  adb shell settings put secure accessibility_enabled 1
  adb shell cmd notification allow_listener "$PKG/$PKG.device.SeyalNotificationListener" 2>/dev/null || true
  adb shell cmd notification allow_dnd "$PKG" 2>/dev/null || true
  adb shell appops set "$PKG" WRITE_SETTINGS allow 2>/dev/null || true
  sleep 3
  if adb shell dumpsys accessibility 2>/dev/null | grep -q "SeyalAccessibilityService"; then echo "   screen control: ON"
  else echo "   !! screen control did not turn on"; echo "Screen control did not turn on in the emulator." >> e2e-warnings.txt; fi
  # A message-like notification to read.
  adb shell "cmd notification post -S bigtext -t 'Amma' seyaltest 'Come home early today, we have guests'" >/dev/null 2>&1 || true
fi


pull() {
  adb shell run-as "$PKG" cat files/seyal/index.json > index.json 2>/dev/null || echo "[]" > index.json
  local id
  id=$(python3 -c "import json;d=json.load(open('index.json'));t=[e for e in d if not str(e.get('source','')).startswith('child')];print(t[0]['id'] if t else '')" 2>/dev/null)
  [ -n "$id" ] && adb shell run-as "$PKG" cat "files/seyal/sessions/$id.json" > "sessions/$id.json" 2>/dev/null
}
pull_all() {
  for id in $(python3 -c "import json;[print(e['id']) for e in json.load(open('index.json'))]" 2>/dev/null); do
    adb shell run-as "$PKG" cat "files/seyal/sessions/$id.json" > "sessions/$id.json" 2>/dev/null || true
  done
}
front() { # bring Seyal back if another app (dialer, calendar, browser) is in front
  if ! adb shell dumpsys activity activities 2>/dev/null | grep -E "topResumedActivity|mResumedActivity" | grep -q "$PKG"; then
    adb shell input keyevent KEYCODE_ENDCALL; sleep 1
    adb shell am start -n "$PKG/.ui.MainActivity" >/dev/null 2>&1
  fi
}

CMDS="$HERE/e2e-commands.txt"
[ "$FULL" = "full" ] && CMDS="$HERE/e2e-commands-full.txt"
n=0
while IFS= read -r cmd <&3 || [ -n "$cmd" ]; do
  [ -z "$cmd" ] && continue
  case "$cmd" in \#*) continue ;; esac
  n=$((n + 1)); tag=$(printf "%02d" "$n")
  # A line starting with "> " is a follow-up in the same conversation.
  follow=0
  case "$cmd" in "> "*) follow=1; cmd="${cmd#> }" ;; esac
  echo "== [$tag] $cmd$([ $follow = 1 ] && echo '  (follow-up)')"
  pull; before=$(python3 "$HERE/e2e_state.py" count index.json); calls_before=$(python3 "$HERE/e2e_state.py" calls index.json sessions)
  start=$(date +%s)
  q=${cmd//\'/\'\\\'\'}   # keep apostrophes intact through adb's shell
  if [ $follow = 1 ]; then adb shell "am broadcast -n $RCV --es say '$q'" >/dev/null
  else adb shell "am broadcast -n $RCV --es command '$q'" >/dev/null; fi
  finished=0
  approved=0; answered=0; state="?"; status=""; shots=0
  for i in $(seq 1 100); do
    sleep 3
    pull
    if [ $follow = 1 ]; then
      [ "$(python3 "$HERE/e2e_state.py" calls index.json sessions)" -le "$calls_before" ] && continue
    else
      [ "$(python3 "$HERE/e2e_state.py" count index.json)" -le "$before" ] && continue
    fi
    state=$(python3 "$HERE/e2e_state.py" newest index.json sessions)
    status=${state%% *}
    # Anything waiting (this task, or a sub-task of it): approve it / answer it, like a user would.
    if [ "$(python3 "$HERE/e2e_state.py" anywaiting index.json)" = "yes" ] && [ $approved -lt 12 ]; then
      case "$state" in *WAITING_USER*) [ $answered -lt 3 ] && shot "e2e/$tag-question$answered.png" ;; *) [ $approved -lt 1 ] && shot "e2e/$tag-approval.png" ;; esac
      adb shell "am broadcast -n $RCV --ez approve_all true --es answer 'the first one'" >/dev/null
      approved=$((approved + 1)); answered=$((answered + 1)); continue
    fi
    # A screenshot mid-way shows the agent working inside another app (with its status pill).
    if [ "$FULL" = "full" ] && [ $shots -lt 2 ] && [ $((i % 5)) -eq 0 ]; then shot "e2e/$tag-mid$shots.png"; shots=$((shots + 1)); fi
    case "$status" in DONE|FAILED|CANCELLED) finished=1; break ;; esac
  done
  if [ $finished = 0 ]; then echo "   !! timed out: stopping it"; adb shell "am broadcast -n $RCV --ez cancel_all true" >/dev/null; sleep 3; pull; fi
  secs=$(( $(date +%s) - start ))
  printf "%s\t%s\t%s\n" "$cmd" "$secs" "$state" >> timings.tsv
  echo "   -> $state in ${secs}s"
  shot "e2e/$tag-end.png"     # where the agent left the phone
  front; sleep 2
  shot "e2e/$tag-result.png"  # what Seyal shows
  case "$status" in DONE|FAILED|CANCELLED)
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    stale=$(adb shell cat /sdcard/ui.xml 2>/dev/null | grep -oE 'text="(running|queued|Working[^"]*|Thinking[^"]*)"' | wc -l)
    if [ "$stale" -gt 0 ]; then echo "   !! screen out of date: $stale stale labels"; echo "$tag	$cmd	$stale" >> ui-stale.tsv; fi ;;
  esac
  sleep 2
done 3< "$CMDS"

pull; pull_all
python3 "$HERE/e2e_state.py" report index.json sessions timings.tsv > e2e-report.md
{
  echo; echo "## Screen check"
  if [ -s ui-stale.tsv ]; then echo "Cards still showing working/queued after the session finished:"; echo '```'; cat ui-stale.tsv; echo '```'
  else echo "OK: every finished session showed its final state on screen."; fi
} >> e2e-report.md
{
  echo "## End-to-end test ($FULL)"
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
