@echo off
rem ============================================================
rem PixelPilot RC real-device verification helper (one-click)
rem Installs the debug APK and streams RC-related logcat so you
rem can confirm RC_CHANNELS_OVERRIDE is actually being emitted.
rem
rem Requirements: adb on PATH (Android platform-tools), phone
rem with USB debugging enabled and screen unlocked.
rem
rem Native confirmation line we watch for (log TAG = pixelpilot):
rem   "RC_CHANNELS_OVERRIDE sent xN -> IP:PORT"
rem ============================================================
setlocal enabledelayedexpansion

set "APK=%~1"
if "%APK%"=="" set "APK=app\build\outputs\apk\debug\app-debug.apk"

echo [verify_rc] checking adb...
where adb >nul 2>nul
if errorlevel 1 (
    echo [verify_rc] ERROR: adb not found. Install Android platform-tools and add to PATH.
    goto :end
)

echo [verify_rc] connected devices:
adb devices -l

if exist "%APK%" (
    echo [verify_rc] installing %APK% ...
    adb install -r "%APK%"
) else (
    echo [verify_rc] APK not found: %APK%
    echo [verify_rc] Download the PixelPilot-debug artifact from CI, or build locally.
)

echo [verify_rc] launching PixelPilot...
adb shell am start -n com.openipc.pixelpilot/.VideoActivity >nul 2>nul

echo.
echo [verify_rc] logcat filter (pixelpilot, info). In-app: enable RC, move
echo [verify_rc] sticks or a gamepad. Expected lines:
echo [verify_rc]   "RC_CHANNELS_OVERRIDE sent xN -> IP:PORT"  (we transmitted)
echo [verify_rc]   "RC echo OK: FC applied override (xN)"      (FC accepted it)
echo [verify_rc]   "RC echo MISMATCH xN: K ch differ ..."      (FC ignored/changed)
echo [verify_rc] Press Ctrl+C to stop.
echo.

adb logcat -c
adb logcat -s pixelpilot:I *:S

:end
endlocal
