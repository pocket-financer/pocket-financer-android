# Android Emulator Testing Commands Reference

> This is a command reference, not evidence that current SMS behavior passed.
> Record each fresh run separately and use
> [sms-processing-next-steps.md](sms-processing-next-steps.md) for the active gate.

This document compiles the exact commands, scripts, coordinates, and navigation targets used to build, install, debug, and interact with the application on the Android emulator (`emulator-5554`).

> [!NOTE]
> Android SDK `platform-tools` (containing `adb`) and Miniconda Python are added to the user's global `PATH`. You can run `adb` and `python` directly from any command line session.

---

## 1. Build and Install via Gradle
You can compile and manage the app packages using the project Gradle wrapper directly:
```powershell
# Compile Kotlin source code
.\gradlew.bat compileDebugKotlin

# Run unit tests
.\gradlew.bat testDebugUnitTest

# Build and install the debug APK
.\gradlew.bat installDebug
```

---

## 2. Launching the App
Force-stops and restarts the application by invoking its primary launcher activity:
```powershell
# Force-stop the application
adb -s emulator-5554 shell am force-stop com.pocketfinancer.debug

# Start MainActivity
adb -s emulator-5554 shell am start -n com.pocketfinancer.debug/com.pocketfinancer.MainActivity
```

---

## 3. SMS Pipeline Injection & Processing
To test the JNI local SLM parsing, you can inject sample SMS messages into the emulator's inbox:
```powershell
# Inject a preset dataset of transactional and promotional SMS
python inject_sms.py
```
*After injection:*
1. Open the app on the emulator.
2. Under the **Home** tab, tap the **Process** button on the "Unsynced Messages" banner.
3. Tap **Inspect** to open the local processing monitor and verify the actual
   observable stages, including live decoded output when inference runs.

---

## 4. UI Navigation & Bottom Tab Coordinates
The app uses a bottom navigation bar. Below are the exact tap coordinates for navigating between screens on a standard screen dimension (e.g., `1080x2400`):

| Target Screen | Tap Coordinates | Action / Description |
| :--- | :--- | :--- |
| **Home** | `adb shell input tap 127 2256` | Dashboard, period spend card, recent spends ledger. |
| **Transactions** | `adb shell input tap 403 2256` | Ledger list, account filters, inline pipeline status. |
| **Insights** | `adb shell input tap 678 2256` | Cash Flow, Chai index, merchant charts, distribution graphs. |
| **Settings** | `adb shell input tap 953 2256` | Hardware capabilities, active SLM manager, developer reset. |

---

## 5. UI Interaction & Sheet Controls
Simulates tap and swipe gestures directly on the emulator.

### A. Dump Screen Hierarchy (To find element coordinates)
To inspect what is currently on the screen and locate clickable bounds:
```powershell
# 1. Dump UI hierarchy XML to emulator storage
adb -s emulator-5554 shell uiautomator dump /sdcard/window_dump.xml

# 2. Pull the XML file to the local machine to read it
adb -s emulator-5554 pull /sdcard/window_dump.xml .\window_dump.xml
```

### B. Dismissing the Telemetry Logs Bottom Sheet
If the extraction logs sheet or sync monitor overlay is expanded and you need to dismiss it:
* **Tap Outside:** Tap a coordinate above the sheet (e.g., `adb shell input tap 500 500`).
* **Close Logs Button:** Tap the "Close Logs" button at the top-right of the sheet: `adb shell input tap 909 108`.
* **Hardware Back Key:** Send the Android back button input event: `adb shell input keyevent 4`.

### C. Scrolling the Screen
Performs a swipe gesture from `(start_x, start_y)` to `(end_x, end_y)` to scroll the content:
```powershell
# Scroll down: Swipe from bottom-middle upwards
adb -s emulator-5554 shell input swipe 500 1500 500 500

# Scroll up: Swipe from top-middle downwards
adb -s emulator-5554 shell input swipe 500 500 500 1500
```

---

## 6. Accessing Sandbox Data
Executes secure directory file listing within the application's private storage directory using Android's `run-as`:
```powershell
# List the downloaded model file details
adb -s emulator-5554 shell run-as com.pocketfinancer.debug ls -la files/models/
```

---

## 7. Monitoring Logs
Dumps the device logs to inspect warnings, runtime errors, or crash traces:
```powershell
# Read current logcat buffer
adb -s emulator-5554 logcat -d
```
