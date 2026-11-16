# HoldGrip Aim Trainer (safe standalone module)

This is a separate Android application module under `aim-trainer/`. It does not modify the existing HoldGrip app.

## Scope
- Android floating overlay panel, shown only after the user grants Android's overlay permission.
- Start/stop the panel service from the app.
- Open a standalone aim-practice screen with a moving target.
- Measure practice hits, misses, hit accuracy, and elapsed time locally.
- No interaction with Free Fire internals, memory, network traffic, or player targeting. The panel does not inject touch events into another app and does not claim to increase in-game headshot rates.

## Build
Open `aim-trainer/` as an independent Android Studio project and build the `app` debug variant. Requires Android Studio with Android SDK 35 and JDK 17.

## Permissions
The app requests `SYSTEM_ALERT_WINDOW` for the optional floating panel. On Android 13+, notification permission may be requested by the OS for the foreground-service notification.

## Project status
Initial implementation. Verify build and test overlay behavior on the target Android device before release.
