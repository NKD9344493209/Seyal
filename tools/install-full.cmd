@echo off
REM Installs the latest Seyal Full build on a phone connected by USB (Windows).
REM One-time on the phone: Settings > About phone > tap "Build number" 7 times, then
REM Settings > System > Developer options > USB debugging ON. Plug in the cable and tap "Allow" on the phone.
setlocal
set DIR=%~dp0
set PT=%DIR%platform-tools
if not exist "%PT%\adb.exe" (
  echo Downloading Android platform-tools (adb) ...
  powershell -NoProfile -Command "[Net.ServicePointManager]::SecurityProtocol='Tls12'; Invoke-WebRequest -Uri https://dl.google.com/android/repository/platform-tools-latest-windows.zip -OutFile '%DIR%pt.zip'; Expand-Archive -Force '%DIR%pt.zip' '%DIR%'; Remove-Item '%DIR%pt.zip'"
)
echo Finding the latest Seyal Full build ...
powershell -NoProfile -Command "$r = Invoke-RestMethod https://api.github.com/repos/NKD9344493209/Seyal/releases; $a = $null; foreach ($rel in $r) { $a = $rel.assets | Where-Object { $_.name -like 'Seyal-Full-*.apk' } | Select-Object -First 1; if ($a) { break } }; if (-not $a) { Write-Host 'No Full build found'; exit 1 }; Write-Host ('Downloading ' + $a.name); Invoke-WebRequest -Uri $a.browser_download_url -OutFile '%DIR%Seyal-Full.apk'"
if errorlevel 1 goto fail
echo.
echo Connect the phone by USB and allow USB debugging if it asks.
"%PT%\adb.exe" wait-for-device
"%PT%\adb.exe" install -r -g "%DIR%Seyal-Full.apk"
if errorlevel 1 goto fail
echo.
echo Installed. On the phone: open Seyal, then Control, then "Screen control" > Turn on.
pause
exit /b 0
:fail
echo.
echo Something went wrong. Check: USB debugging on, phone unlocked, "Allow" tapped on the phone.
pause
exit /b 1
