# Black for Android

Black runs a recurring on-screen blackout timer on Android 14 (API 34) and newer. The defaults match the Mac app: every 20 minutes it shows a 10 second warning, followed by a 20 second blackout. The interval can be set in minutes or seconds; warning and blackout durations can also be changed in the app.

The app uses a foreground service and an opaque Android application overlay that fills the available display area, including app window insets. Android may keep system bars, permission screens, picture-in-picture windows, and the keyboard visible above the overlay. The ongoing "Timer running" notification is required while the service monitors the timer and microphone. The separate Cancel alert appears only during the last 10 seconds before blackout and is removed when the warning ends.

Optional app exceptions use Android's Usage Access permission to detect the app currently in front. While an excepted app is active, the countdown pauses and keeps its remaining time.

## Install

1. Install JDK 17 and the Android SDK with API 36 and Build Tools 36.0.0. Set `ANDROID_HOME` or create `local.properties` with `sdk.dir=/absolute/path/to/sdk`.
2. Run `./gradlew testDebugUnitTest assembleDebug`.
3. Install `app/build/outputs/apk/debug/app-debug.apk` with `adb install -r app/build/outputs/apk/debug/app-debug.apk`, or copy it to your Android phone and open it there. The debug APK is signed for sideloading.
4. Open Black, grant **Display over other apps**, and tap **Start**. Allow notifications to see the warning's **Cancel** action.

## Controls and behavior

- **Start** saves the settings and begins a full interval. **Stop** ends the service. **Save settings** restarts the interval with the new values. **Test all functions** runs a guided, accelerated check of notification cancellation, warning cancellation, the blackout, and its three-tap dismissal. The normal saved settings resume afterward.
- **Appearance** offers System, Light, and Dark themes. System follows the phone's appearance setting; the other choices override it for Black.
- **App exceptions** opens a scrollable exception list. Apps can be removed there or added from a fuzzy-searchable picker. Grant Usage Access on that screen to activate automatic pausing.
- **Check for updates** reads the public GitHub Releases list, including previews. When a newer Black APK is available, it downloads it, verifies its checksum, package name, version, and signing certificate, then opens Android's installation confirmation. The installer callback is allowed to open only while Black is visible. The first update may require allowing installs from Black in Android settings.
- The warning has a **Cancel blackout** button. With notifications enabled, a high-importance notification appears about 10 seconds before the blackout with a **Cancel** action and an action that adds the current app as an exception. Three quick taps on the black screen cancel an active blackout. Canceling starts a fresh interval.
- The countdown pauses while another app records audio. If recording begins during a warning or blackout, that sequence is dismissed; a full interval begins when recording ends.
- Locking or turning off the screen pauses the countdown and dismisses an active warning or blackout. Unlocking within one minute resumes the remaining countdown; after one minute, it starts a full interval.
- If the schedule was enabled, Android restarts it after a reboot or app update. Opening Black also restores an enabled schedule if an installer or device-specific process manager stopped its service. Force-stop and some device battery restrictions can still prevent background recovery until the app is opened. If overlay permission is revoked, the service stops and the app shows that permission is needed.

## Notification choices

Black uses separate Android notification channels: **Black schedule** for the required running-service card and **Blackout warnings** for the final Cancel alert. In the phone's **Settings → Apps → Black → Notifications**, some devices let you silence or turn off only **Black schedule** while leaving **Blackout warnings** enabled. Android can still list the foreground service under **Active apps**. Disabling all Black notifications also hides the Cancel alert.

An alarm-based schedule could avoid the running-service card, but Android requires exact-alarm access on many devices, and the app could no longer continuously detect microphone use between alarms.

## APK updates

The in-app updater expects a public release tagged like `v1.0.5` with an asset named `black-android-v1.0.5-debug.apk` or `black-android-v1.0.5.apk`. Increment both `versionCode` and `versionName` for each APK. Android requires the new APK to use the same signing key as the installed one; the updater checks this before opening the installer. These preview releases use a debug signing key, so builds from a different machine or a regenerated debug key cannot update an existing installation. Android always shows its own installation confirmation.

## Build notes

The Gradle wrapper downloads Gradle 9.4.1. The app uses Android Gradle Plugin 9.2.1 and API 36. `SchedulerCore` contains the timer logic and has local unit tests.

Datadog uses application ID `2151fa26-2c6d-47ae-96a5-f4dabf4a6248` and its public client token on the EU1 site. Override the embedded client token with the `DATADOG_RUM_CLIENT_TOKEN` Gradle property or environment variable when needed. RUM tracks activities, UI interactions, errors, crashes, and main-thread tasks longer than 100 ms.

Structured Datadog logs cover user commands, saved timer settings, update progress and failures, service commands, phase changes, warning notification lifecycle, screen and microphone state changes, restart recovery, and service health. Health is emitted when the service starts and every five minutes afterward. Logs use service and logger name `black-android`, include app version metadata, and are linked to the active RUM session. Debug APKs also write these events to Logcat.

## Repeatable emulator eye test

Start an Android 14+ emulator, build the APK, and run:

```sh
./gradlew testDebugUnitTest assembleDebug
python3 scripts/emulator_eye_test.py
```

The script works only with an `emulator-*` ADB device. It reinstalls the APK, **clears Black's emulator app data**, grants overlay, notification, and usage access permissions, captures System and Dark appearance, tests fuzzy app search plus exception add/remove, then sets an 8-second interval in the app. It verifies the warning notification's exception action pauses the timer and then runs the guided functional test, including notification cancellation, warning cancellation, blackout display, and three-tap dismissal. It also checks countdown recovery plus screen-off and wake behavior, then stops the service. Screenshots and a browsable gallery are written to `artifacts/eye-tests/index.html`. Run the same command again for another pass. The generated artifacts are ignored by Git. Add `--include-reboot` for the slower boot recovery check.

If you need an emulator, install the Android SDK packages `emulator` and `system-images;android-36;default;arm64-v8a`, then create an AVD with `avdmanager create avd -n black_api36 -k 'system-images;android-36;default;arm64-v8a'`. Start it with `emulator -avd black_api36` before running the script.

Microphone detection and behavior on a physical phone still need device testing. The app was also tested manually for lock, unlock, and reboot recovery on an Android 16 emulator.
