#!/usr/bin/env bash
# Installs the latest Seyal Full build on a phone connected by USB (Git Bash / macOS / Linux).
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
ADB="$DIR/platform-tools/adb"
[ -x "$ADB.exe" ] && ADB="$ADB.exe"
if [ ! -x "$ADB" ]; then
  case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) os=windows;; Darwin) os=darwin;; *) os=linux;; esac
  echo "Downloading Android platform-tools (adb) ..."
  curl -sL -o "$DIR/pt.zip" "https://dl.google.com/android/repository/platform-tools-latest-$os.zip"
  (cd "$DIR" && unzip -q -o pt.zip && rm pt.zip)
  [ -x "$DIR/platform-tools/adb.exe" ] && ADB="$DIR/platform-tools/adb.exe" || ADB="$DIR/platform-tools/adb"
fi
echo "Finding the latest Seyal Full build ..."
url=$(curl -sL https://api.github.com/repos/NKD9344493209/Seyal/releases | grep -o '"browser_download_url": *"[^"]*Seyal-Full-[0-9]*\.apk"' | head -1 | cut -d'"' -f4)
[ -z "$url" ] && { echo "No Full build found"; exit 1; }
echo "Downloading $(basename "$url")"
curl -sL -o "$DIR/Seyal-Full.apk" "$url"
echo "Connect the phone by USB and allow USB debugging if it asks."
"$ADB" wait-for-device
"$ADB" install -r -g "$DIR/Seyal-Full.apk"
echo "Installed. On the phone: open Seyal, then Control, then Screen control > Turn on."
