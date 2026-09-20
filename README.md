# Black for Android

Black runs a recurring on-screen blackout timer on Android 14 (API 34) and newer. The defaults match the Mac app: every 20 minutes it shows a 10 second warning, followed by a 20 second blackout. The interval can be set in minutes or seconds; warning and blackout durations can also be changed in the app.

The app uses a foreground service and an Android application overlay. Android may keep system bars, permission screens, and the keyboard visible above the overlay. The ongoing "Timer running" notification is required while the service monitors the timer and microphone. The separate Cancel alert appears only during the last 10 seconds before blackout and is removed when the warning ends.

## Install

1. Install JDK 17 and the Android SDK with API 36 and Build Tools 36.0.0. Set `ANDROID_HOME` or create `local.properties` with `sdk.dir=/absolute/path/to/sdk`.
2. Run `./gradlew testDebugUnitTest assembleDebug`.
3. Install `app/build/outputs/apk/debug/app-debug.apk` with `adb install -r app/build/outputs/apk/debug/app-debug.apk`, or copy it to your Android phone and open it there. The debug APK is signed for sideloading.
4. Open Black, grant **Display over other apps**, and tap **Start**. Allow notifications to see the warning's **Cancel** action.

## Controls and behavior

- **Start** saves the settings and begins a full interval. **Stop** ends the service. **Save settings** restarts the interval with the new values. **Test now** starts the warning immediately.
- The warning has a **Cancel blackout** button. With notifications enabled, a high-importance notification appears about 10 seconds before the blackout with a **Cancel** action. Three quick taps on the black screen cancel an active blackout. Canceling starts a fresh interval.
- The countdown pauses while another app records audio. If recording begins during a warning or blackout, that sequence is dismissed; a full interval begins when recording ends.
- Locking or turning off the screen pauses the countdown and dismisses an active warning or blackout. Unlocking within one minute resumes the remaining countdown; after one minute, it starts a full interval.
- If the schedule was enabled, Android restarts it after a reboot or app update. Force-stop and some device battery restrictions can prevent this. If overlay permission is revoked, the service stops and the app shows that permission is needed.

## Build notes

The Gradle wrapper downloads Gradle 9.4.1. The app uses Android Gradle Plugin 9.2.1 and API 36. `SchedulerCore` contains the timer logic and has local unit tests.

## Repeatable emulator eye test

Start an Android 14+ emulator, build the APK, and run:

```sh
./gradlew testDebugUnitTest assembleDebug
python3 scripts/emulator_eye_test.py
```

The script works only with an `emulator-*` ADB device. It reinstalls the APK, **clears Black's emulator app data**, grants overlay and notification permissions, then sets an 8-second interval in the app. It checks the countdown, warning overlay, 10-second notification and its Cancel action, blackout overlay, three-tap dismissal, screen-off and wake behavior, and Stop control. Screenshots and a browsable gallery are written to `artifacts/eye-tests/index.html`. Run the same command again for another pass. The generated artifacts are ignored by Git. Add `--include-reboot` for the slower boot recovery check.

If you need an emulator, install the Android SDK packages `emulator` and `system-images;android-36;default;arm64-v8a`, then create an AVD with `avdmanager create avd -n black_api36 -k 'system-images;android-36;default;arm64-v8a'`. Start it with `emulator -avd black_api36` before running the script.

Microphone detection and behavior on a physical phone still need device testing. The app was also tested manually for lock, unlock, and reboot recovery on an Android 16 emulator.
