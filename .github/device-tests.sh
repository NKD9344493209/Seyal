#!/usr/bin/env bash
# Runs on the emulator: smoke test (no key), then the end-to-end test (with the key secret).
# $1 = APK, $2 = "lite" | "full"
HERE="$(cd "$(dirname "$0")" && pwd)"
bash "$HERE/smoke.sh" "$1"; smoke=$?
bash "$HERE/e2e.sh" "${2:-lite}"; e2e=$?
echo "smoke=$smoke e2e=$e2e"
[ $smoke -eq 0 ] && [ $e2e -eq 0 ]
