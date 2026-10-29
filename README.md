# StudySnap

An offline Android study app that turns typed PDF notes into short reading sections and local study reminders. Native Java/Android; no Expo, account, API key, server, or internet permission. The original Node PDF server remains available independently at the repository root.

## Download the Android APK

**[Download StudySnap 1.1.0 APK](https://github.com/hosannaute/studysnap-pdf-server/raw/refs/heads/main/downloads/StudySnap.apk)** · [SHA-256 checksum](downloads/StudySnap.apk.sha256)

Download in your phone’s browser, open the APK in Files, and allow installation from that source when Android asks. No Expo Go is needed. If GitHub shows a file page, choose **Download raw file**. Android 8.0 or newer is required.

This is a debug-signed testing build, not a production/Play Store release. It uses the same development signing key as the earlier APK, so it can update that installation while keeping its data. Future builds need the same signing key to update it in place; signing keys are never committed. Production distribution needs your own signing key.

### New in 1.1.0

- PDF imports show page progress, support cancellation before saving, and explain scanned, password-protected, damaged, and over-limit files.
- Reminder status explains blocked notifications, quiet hours, pauses, snoozes, and approximate next delivery.
- **Send test reminder** posts immediately (including during quiet hours or a pause) without advancing reading progress.
- **Pause for today** pauses all subjects until local midnight; **Resume reminders** ends the pause.
- **Snooze 15 minutes** delays the next reminder for one subject. Snooze and pause actions also appear in regular notifications.
- **Settings → Export backup / Restore backup** saves notes, reading position, per-subject intervals, and study history to portable JSON. Backups are unencrypted and limited to 25 MB, 500 subjects, 10,000 sections, 8 million text characters, and 100,000 history events. Anyone with a backup can read the notes. Choosing a cloud file provider may sync the file through that provider.
- Restoring validates the whole backup before writing and adds separate copies with reminders off. Existing notes and global preferences remain unchanged; restoring twice creates duplicates. The database upgrade preserves existing notes.

## Android app

The app lives in `android/`. It includes onboarding, notification permission, a library dashboard, PDF import/processing, a swipeable reader, per-subject reminder settings, quiet hours, and study progress.

- Android 8.0 (API 26) or newer.
- PDFs: at most 100 pages / 30 MB / 2 million extracted characters. Scanned/image PDFs require external OCR. Protected PDFs are rejected.
- PDFBox-Android extracts text on-device; SQLite stores sections, preferences use SharedPreferences. Imported PDF copies are temporary and removed after processing.
- Sections aim for 200 words and preserve sentence boundaries. A single sentence longer than 200 words remains intact.
- Reminders are off for new subjects; enable them from the library or subject settings. Intervals range from 5 to 120 minutes.
- Local notifications open the matching subject and section. Dismissal does not stop the next reminder. Alarms restore after reboot and app updates.
- Android battery policy may delay inexact reminders and prevent banners; notification permission and channel settings must allow them. Force-stopping the app prevents reminders until it is reopened. No exact-alarm or battery-exemption permissions are requested.
- Quiet hours can cross midnight; matching hours disable the quiet period. Scheduling attempts to keep subjects at least 60 seconds apart; Android can batch their actual delivery.
- “Explored” means a section was opened or a notification was posted, not proof it was read. Daily/weekly counts count distinct sections. Deleting a subject removes its associated history. Uninstalling deletes local data; backup is disabled.

### Build an installable APK

Install a **full JDK 17 or 21**, Android SDK platform 35 and build-tools 35.0.0. Set `ANDROID_HOME` or add `sdk.dir=/absolute/path/to/sdk` to ignored `android/local.properties`.

```sh
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

APK: `android/app/build/outputs/apk/debug/app-debug.apk`. This is a **debug-signed testing build**, suitable for downloading and sideloading. Copy it to your Android phone, open it in Files, and allow installation from that source when Android asks. No Expo Go is needed. Production distribution requires your own securely managed signing key; the unsigned release build is not an installable substitute.

On an attached test device:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.studysnap.app android.permission.POST_NOTIFICATIONS # Android 13+
./gradlew :app:connectedDebugAndroidTest
```

Instrumentation tests reset the test installation's local database; do not run them against real study data. They cover offline PDF extraction and persistence, import cancellation and errors, backup round trips and invalid backups, database migration, pause/snooze/test notifications, notification posting/cancellation and tapping, reading order, and overnight quiet hours. JVM tests cover chunking, Unicode previews, quiet-hour boundaries, and scheduling collisions.

Before production, also verify on a real phone: background delivery under its battery policy, notification taps, reboot restoration, denied permissions, SAF import from your file provider, scanned/encrypted PDFs, and reader swipes. Emulator results do not establish manufacturer-specific battery behavior. The MVP has no iOS build, OCR, cloud sync, AI, login, or payments. A support contact is not configured.

### Cloud workspace

The validated toolchain is retained under `/workspace/android-tools`: `jdk-21.0.9+10`, `gradle-8.13`, and `sdk`. Use the existing checkout; tasks are already isolated, so a new Git worktree is unnecessary. Platform proxy/TLS trust may need explicit Java options:

```sh
export JAVA_HOME=/workspace/android-tools/jdk-21.0.9+10
export ANDROID_HOME=/workspace/android-tools/sdk
export GRADLE_OPTS="-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts -Dhttps.proxyHost=proxy -Dhttps.proxyPort=8080 -Dhttp.proxyHost=proxy -Dhttp.proxyPort=8080"
cd /workspace/studysnap-pdf-server/android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Keep TLS verification enabled. Use supported execution approval if the sandbox blocks network access or local emulator sockets.

## Existing PDF server

Requires Node. At the repository root:

```sh
npm install --no-save --package-lock=false
npm start
```

`GET /` reports health. `POST /extract` takes multipart field `file`. This server is not used by the Android app. Its old PDF parser rejects some valid PDFs, and npm warns about the existing Multer 1.x dependency; those server dependencies have not been upgraded as part of the Android app.
