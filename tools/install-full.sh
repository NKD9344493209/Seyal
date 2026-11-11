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

echo "Finding the newest Seyal Full build ..."
url=$(curl -sSL https://api.github.com/repos/NKD9344493209/Seyal/releases | grep -o '"browser_download_url": *"[^"]*Seyal-Full-[0-9]*\.apk"' | head -1 | cut -d'"' -f4)
[ -z "$url" ] && fail "No Full build found on GitHub."
echo "Downloading $(basename "$url") ..."
curl -sSL -o "$APK" "$url" || fail "Download failed."

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

echo "Installing Seyal ..."
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
echo "Installed."

echo "Switching on screen control, notification access and special access ..."
"$ADB" shell 'S=com.seyal.app/com.seyal.app.device.SeyalAccessibilityService; C=$(settings get secure enabled_accessibility_services); case x$C in *com.seyal.app*) ;; xnull|x) settings put secure enabled_accessibility_services $S ;; *) settings put secure enabled_accessibility_services $C:$S ;; esac; settings put secure accessibility_enabled 1' >/dev/null 2>&1
"$ADB" shell cmd notification allow_listener $PKG/$PKG.device.SeyalNotificationListener >/dev/null 2>&1
"$ADB" shell cmd notification allow_dnd $PKG >/dev/null 2>&1
for op in GET_USAGE_STATS WRITE_SETTINGS SCHEDULE_EXACT_ALARM ACCESS_RESTRICTED_SETTINGS; do "$ADB" shell appops set $PKG $op allow >/dev/null 2>&1; done
"$ADB" shell dumpsys deviceidle whitelist +$PKG >/dev/null 2>&1
"$ADB" shell am start -n $PKG/.ui.MainActivity >/dev/null 2>&1

if "$ADB" shell settings get secure enabled_accessibility_services 2>/dev/null | grep -q $PKG; then echo "  Screen control ......... ON"; else echo "  Screen control ......... turn it on in Seyal: the setup card at the top"; fi
if "$ADB" shell settings get secure enabled_notification_listeners 2>/dev/null | grep -q $PKG; then echo "  Notification access .... ON"; else echo "  Notification access .... turn it on in Seyal: the setup card at the top"; fi
echo; echo "Done. Seyal is open on the phone. Add your AI key in Control > AI, then try it."
