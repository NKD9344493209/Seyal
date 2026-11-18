#!/usr/bin/env bash
# Installs the newest Seyal Full build on a phone connected by USB (Git Bash / macOS / Linux), and switches on
# everything it needs (screen control, notification access, screen time, do-not-disturb, settings, no battery limits).
# One-time on the phone: Settings > About phone > tap "Build number" 7 times, then
# Settings > System > Developer options > USB debugging ON. Plug in the cable and tap "Allow" on the phone.
# On Windows: double-click install-full.cmd, or in Git Bash run:  bash tools/install-full.sh
DIR="$(cd "$(dirname "$0")" && pwd)"
PKG=com.seyal.app
APK="$DIR/Seyal-Full.apk"
fail() { echo; echo "$1"; echo "Check: USB debugging on, phone unlocked, \"Allow\" tapped on the phone. Then run this again."; exit 1; }

echo; echo " Seyal installer"; echo " ==============="
ADB="$DIR/platform-tools/adb"; [ -f "$ADB.exe" ] && ADB="$ADB.exe"
if [ ! -f "$ADB" ]; then
  case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) os=windows;; Darwin) os=darwin;; *) os=linux;; esac
  echo "Downloading Android platform-tools (adb) ..."
  curl -sSL -o "$DIR/pt.zip" "https://dl.google.com/android/repository/platform-tools-latest-$os.zip" || fail "Could not download adb."
  if command -v unzip >/dev/null 2>&1; then (cd "$DIR" && unzip -q -o pt.zip)
  else powershell -NoProfile -Command "Expand-Archive -Force '$(cygpath -w "$DIR/pt.zip" 2>/dev/null || echo "$DIR/pt.zip")' '$(cygpath -w "$DIR" 2>/dev/null || echo "$DIR")'"; fi
  rm -f "$DIR/pt.zip"
  ADB="$DIR/platform-tools/adb"; [ -f "$ADB.exe" ] && ADB="$ADB.exe"
  [ -f "$ADB" ] || fail "Could not unpack adb."
  chmod +x "$ADB" 2>/dev/null
fi

# The version this folder holds (what your last "git am" + "git push" asked GitHub to build).
WANT=$(grep -o 'versionName = "[^"]*"' "$DIR/../app/build.gradle.kts" 2>/dev/null | head -1 | cut -d'"' -f2)
REL=""
releases() { REL=$(curl -sSL https://api.github.com/repos/NKD9344493209/Seyal/releases 2>/dev/null); }
newest() { echo "$REL" | grep -o '"browser_download_url": *"[^"]*Seyal-Full-[0-9]*\.apk"' | head -1 | cut -d'"' -f4; }
# When GitHub could not build a push it publishes "failed-N" with the reasons. If the newest thing on GitHub is such a
# failure, and it is the failure of what this folder holds, waiting is pointless: say so, loudly, with the reason.
# (This happened with 3.17: the build failed, the phone kept 3.16, and a day of testing was done on the old version.)
HEAD_SHA=$(git -C "$DIR/.." rev-parse HEAD 2>/dev/null)
build_failed() {
  tag=$(echo "$REL" | grep -o '"tag_name": *"[^"]*"' | head -1 | cut -d'"' -f4)
  case "$tag" in failed-*) ;; *) return 1 ;; esac
  why=$(curl -sSL "https://github.com/NKD9344493209/Seyal/releases/download/$tag/build-errors.txt" 2>/dev/null)
  [ -n "$HEAD_SHA" ] && ! echo "$why" | head -1 | grep -q "$HEAD_SHA" && return 1
  echo
  echo "  ============================================================"
  echo "  GITHUB COULD NOT BUILD YOUR LATEST PUSH ($tag)."
  echo "  Version $WANT was NOT made. The phone still has ${got:-the old version}."
  echo "  Do not test now. Send Claude this line:   build $tag"
  echo "  ============================================================"
  echo "  The reason GitHub gave:"
  echo "$why" | grep -E "FAILED|^ +FAIL |^e: |error:|AssertionError|What went wrong" | grep -v "daemon" | head -8 | sed 's/^/    /'
  echo
  return 0
}
installed() { "$ADB" shell dumpsys package $PKG 2>/dev/null | tr -d '\r' | grep -m1 -o 'versionName=[^ ]*' | cut -d= -f2; }

echo; echo "Connect the phone by USB. If the phone asks \"Allow USB debugging?\", tap Allow."
"$ADB" start-server >/dev/null 2>&1
for i in $(seq 1 60); do
  state=$("$ADB" get-state 2>/dev/null | tr -d '\r')
  [ "$state" = "device" ] && break
  [ $i = 1 ] && echo "Waiting for the phone ..."
  [ $i = 10 ] && echo "Still waiting: unlock the phone, check the cable, and tap Allow on the phone."
  [ $i = 60 ] && { "$ADB" devices; fail "No phone found."; }
  sleep 2
done
echo "Phone connected."

install_apk() {
  out=$("$ADB" install -r -d -g "$APK" 2>&1)
  if ! echo "$out" | grep -q Success; then
    if echo "$out" | grep -q INSTALL_FAILED_UPDATE_INCOMPATIBLE; then
      echo "An older Seyal with a different signature is on the phone. Removing it and installing fresh ..."
      "$ADB" uninstall $PKG >/dev/null 2>&1
      out=$("$ADB" install -g "$APK" 2>&1)
    fi
  fi
  if ! echo "$out" | grep -q Success; then
    echo "$out"
    echo "$out" | grep -q INSTALL_FAILED_USER_RESTRICTED && echo "On Xiaomi/Redmi/POCO: Developer options > turn on \"Install via USB\". On Oppo/Realme/Vivo: turn on \"Disable permission monitoring\"."
    fail "Install failed."
  fi
}

# GitHub needs a few minutes to build after a push. Installing before it has finished puts the OLD version on the
# phone (the 3.13 build was ready at 11:38 and the test recordings began at 11:39). So: install the newest build, and if it is not the
# version of this folder yet, wait for the right one and install that.
last=""; got=""
FAILED=0
for try in $(seq 1 30); do
  releases
  url=$(newest)
  if [ -z "$url" ]; then [ -n "$last" ] && { sleep 60; continue; }; fail "No Full build found on GitHub."; fi
  if [ "$url" != "$last" ]; then
    echo "Downloading $(basename "$url") ..."
    curl -sSL -o "$APK" "$url" || fail "Download failed."
    echo "Installing ..."
    install_apk
    last="$url"
    got=$(installed)
  fi
  if [ -z "$WANT" ] || [ -z "$got" ] || [ "${got#$WANT}" != "$got" ]; then break; fi
  if build_failed; then FAILED=1; break; fi
  if [ $try = 1 ]; then echo; echo "GitHub is still building version $WANT (your latest push). The newest finished build is $got."; fi
  if [ $try = 30 ]; then echo "The $WANT build has not appeared after 30 minutes. See github.com/NKD9344493209/Seyal/actions. The phone has $got for now."; break; fi
  echo "  Waiting for $WANT ... checking again in 1 minute ($try of 30). Leave this window open."
  sleep 60
done
echo; echo "  INSTALLED ON THE PHONE: Seyal ${got:-(version unknown)}"
[ $FAILED = 1 ] && echo "  (This is the OLD version: the new one could not be built. See the box above.)"
[ -n "$WANT" ] && [ -n "$got" ] && [ "${got#$WANT}" != "$got" ] && echo "  This is the newest version ($WANT). The chat screen shows \"v$WANT\" under the title."
echo

echo "Switching on screen control, notification access and special access ..."
"$ADB" shell 'S=com.seyal.app/com.seyal.app.device.SeyalAccessibilityService; C=$(settings get secure enabled_accessibility_services); case x$C in *com.seyal.app*) ;; xnull|x) settings put secure enabled_accessibility_services $S ;; *) settings put secure enabled_accessibility_services $C:$S ;; esac; settings put secure accessibility_enabled 1' >/dev/null 2>&1
"$ADB" shell cmd notification allow_listener $PKG/$PKG.device.SeyalNotificationListener >/dev/null 2>&1
"$ADB" shell cmd notification allow_dnd $PKG >/dev/null 2>&1
# Background location: for automations like "when Amma asks where I am, send my location".
"$ADB" shell pm grant $PKG android.permission.ACCESS_BACKGROUND_LOCATION >/dev/null 2>&1
# Lets Seyal switch its own screen control on and off (Control > "Screen control only while Seyal works"),
# so Google Pay and bank apps are not blocked the rest of the time.
"$ADB" shell pm grant $PKG android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1
for op in GET_USAGE_STATS WRITE_SETTINGS SCHEDULE_EXACT_ALARM ACCESS_RESTRICTED_SETTINGS MANAGE_EXTERNAL_STORAGE RUN_IN_BACKGROUND RUN_ANY_IN_BACKGROUND; do "$ADB" shell appops set $PKG $op allow >/dev/null 2>&1; done
"$ADB" shell dumpsys deviceidle whitelist +$PKG >/dev/null 2>&1
# Xiaomi's own "Autostart" switch (op 10008): without it, automations stop when Seyal is closed.
"$ADB" shell appops set $PKG 10008 allow >/dev/null 2>&1
MAKER=$("$ADB" shell getprop ro.product.manufacturer 2>/dev/null | tr -d '\r' | tr 'A-Z' 'a-z')
"$ADB" shell am start -n $PKG/.ui.MainActivity >/dev/null 2>&1

if "$ADB" shell settings get secure enabled_accessibility_services 2>/dev/null | grep -q $PKG; then echo "  Screen control ......... ON"; else echo "  Screen control ......... turn it on in Seyal: the setup card at the top"; fi
if "$ADB" shell dumpsys package $PKG 2>/dev/null | grep -q "android.permission.WRITE_SECURE_SETTINGS: granted=true"; then echo "  Switch itself on/off ... ALLOWED (for Google Pay: Control > Screen control only while Seyal works)"
else echo "  Switch itself on/off ... not allowed by this phone"; case "$MAKER" in *xiaomi*|*redmi*|*poco*) echo "     Xiaomi: Settings > Additional settings > Developer options > turn ON \"USB debugging (Security settings)\", then run this again.";; esac; fi
if "$ADB" shell settings get secure enabled_notification_listeners 2>/dev/null | grep -q $PKG; then echo "  Notification access .... ON"; else echo "  Notification access .... turn it on in Seyal: the setup card at the top"; fi
case "$MAKER" in
  *xiaomi*|*redmi*|*poco*) echo; echo "Xiaomi/Redmi/POCO: also open Settings > Apps > Seyal, turn ON \"Autostart\", and set Battery saver to \"No restrictions\"."; echo "Without these, automations (\"when Amma messages...\") stop working when Seyal is closed." ;;
  *oppo*|*realme*|*vivo*|*oneplus*|*iqoo*) echo; echo "Oppo/Realme/Vivo/OnePlus: also open Settings > Apps > Seyal > Battery, allow background activity and auto-launch,"; echo "so automations keep working when Seyal is closed." ;;
  *samsung*) echo; echo "Samsung: Settings > Apps > Seyal > Battery > Unrestricted, so automations keep working when Seyal is closed." ;;
esac
echo; echo "Done. Seyal is open on the phone. Add your AI key in Control > AI, then try it."
