@echo off
REM Installs the newest Seyal Full build over USB and switches on everything it needs.
REM It runs install-full.sh with Git Bash (comes with Git for Windows).
setlocal
set "GITBASH=%ProgramFiles%\Git\bin\bash.exe"
if not exist "%GITBASH%" set "GITBASH=%LocalAppData%\Programs\Git\bin\bash.exe"
if not exist "%GITBASH%" set "GITBASH=%ProgramW6432%\Git\bin\bash.exe"
if exist "%GITBASH%" "%GITBASH%" "%~dp0install-full.sh"
if not exist "%GITBASH%" echo Git for Windows was not found. Open Git Bash in this folder and run:  bash install-full.sh
pause
