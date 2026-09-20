# Black for Android

Black runs a recurring on-screen blackout timer on Android 14 (API 34) and newer. The defaults match the Mac app: every 20 minutes it shows a 10 second warning, followed by a 20 second blackout. The interval, warning, and blackout durations can be changed in the app.

The app uses a foreground service and an Android application overlay. Android may keep system bars, permission screens, and the keyboard visible above the overlay. The ongoing notification is part of running the timer.

## Install

1. Install JDK 17 and the Android SDK with API 36 and Build Tools 36.0.0. Set `ANDROID_HOME` or create `local.properties` with `sdk.dir=/absolute/path/to/sdk`.
2. Run `./gradlew testDebugUnitTest assembleDebug`.
3. Install `app/build/outputs/apk/debug/app-debug.apk` with `adb install -r app/build/outputs/apk/debug/app-debug.apk`, or copy it to your Android phone and open it there. The debug APK is signed for sideloading.
4. Open Black, grant **Display over other apps**, and tap **Start**. Allow notifications to see the warning's **Cancel** action.

## Controls and behavior

- **Start** saves the settings and begins a full interval. **Stop** ends the service. **Save settings** restarts the interval with the new values. **Test now** starts the warning immediately.
- The warning has a **Cancel blackout** button. When notifications are enabled, its notification also has **Cancel**. Three quick taps on the black screen cancel an active blackout. Canceling starts a fresh interval.
- The countdown pauses while another app records audio. If recording begins during a warning or blackout, that sequence is dismissed; a full interval begins when recording ends.
- Locking or turning off the screen dismisses an active sequence. Unlocking starts a full interval by default. The optional setting can preserve the remaining countdown instead.
- If the schedule was enabled, Android restarts it after a reboot or app update. Force-stop and some device battery restrictions can prevent this. If overlay permission is revoked, the service stops and the app shows that permission is needed.

## Build notes

The Gradle wrapper downloads Gradle 9.4.1. The app uses Android Gradle Plugin 9.2.1 and API 36. `SchedulerCore` contains the timer logic and has local unit tests. An Android 14+ phone or emulator is needed to verify overlay appearance, microphone detection, lock behavior, notification actions, and reboot recovery.
