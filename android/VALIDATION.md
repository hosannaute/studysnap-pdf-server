# Android validation — 29 October 2026

## Version 1.1.0

- Gradle wrapper 8.13, Android Gradle Plugin 8.9.2, Temurin JDK 21, SDK 35.
- APK build, Android lint, and all **7 JVM tests passed** (no failures or skips).
- Version code 2; Android 8.0+; APK Signature Scheme v2 verification passed.
- Development signing certificate matches the earlier APK, permitting an in-place update. No signing keys are in Git.
- Main APK permissions: notifications, reboot restoration, vibration. No internet permission.
- Android instrumentation coverage includes the original offline workflow plus import progress/errors/cancellation, backup/restore and invalid-backup safety, preserving data during the database upgrade, pause/snooze behavior, test notification progress isolation, and screen controls.
- Current instrumented execution is pending because the software emulator's guest shell stalled during startup; a restart with Vulkan disabled and longer software-emulation timeouts is being checked. This is not a passing device-test result.

## Prior version 1.0.0

Installed and launched on Android 15 / API 35 x86_64. All **6 instrumentation tests passed**: offline PDF extraction, SQLite persistence/history, rollback, reading order, quiet hours, notification posting/cancellation, notification tap opening the matching section, and basic screen navigation. The first emulator run hit system-wide startup ANRs; the explicit suite passed after startup settled and precompilation completed.

## Real-device checks still required

Background timing and banners under manufacturer battery policies, lock-screen notification taps, reboot restoration, file-provider selection/export/restore, swipe gestures, and denied notification permission. Android can delay inexact alarms. This APK is a debug-signed testing build, not a production/Play Store release.
