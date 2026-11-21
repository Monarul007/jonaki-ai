# Shipping the 1.0.1 fix (office PC)

The fixed source is on `main` (`versionCode 3`, `versionName 1.0.1`, commit `057ec54` and later).
The only missing piece is the **original release signing key**, which lives on the office PC.

Do **not** uninstall the app. Installing the new APK over the old one keeps the downloaded
2.6 GB model and all chats, provided it is signed with the same key.

## On the office PC

1. Get the code:
   ```powershell
   git clone https://github.com/Monarul007/jonaki-ai.git
   cd jonaki-ai
   # or, in an existing checkout:
   git pull origin main
   ```

2. Confirm the release key is where Gradle looks for it:
   ```
   %USERPROFILE%\.android-keys\offlinegemma.jks
   %USERPROFILE%\.android-keys\offlinegemma.properties
   ```
   The properties file needs `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.
   Alternatively set `OFFLINEGEMMA_KEY_PROPS` to the properties file path.

3. Point Gradle at the local Android SDK (`local.properties` is gitignored):
   ```
   sdk.dir=C\:\\Android\\Sdk
   ```

4. Build and test:
   ```powershell
   .\gradlew.bat :app:testDebugUnitTest
   .\gradlew.bat :app:assembleRelease
   ```

5. Check the output:
   ```
   app\build\outputs\apk\release\app-release.apk
   ```
   If you only see `app-release-unsigned.apk`, the key properties were not found — do not ship
   that file; it cannot install over the existing app.

6. Install over the old app (same package, same key, `versionCode 3` > `2`):
   ```powershell
   adb install -r app\build\outputs\apk\release\app-release.apk
   ```

7. Verify on the phone:
   - About/Settings shows **1.0.1**.
   - **+ → Camera** opens a preview inside Jonaki — the system camera app must not open.
   - Take a photo: it becomes a draft chip and the model stays ready.
   - After the capture, `adb shell dumpsys activity exit-info com.offlinegemma.app` shows no
     new process-exit record.

## What this build changes

- The camera is in-app (CameraX): preview, shutter and front/back switch. This removes the
  `ACTION_IMAGE_CAPTURE` path that let MIUI's `camopt_killer` SIGKILL the process.
- `MainActivity.onResume` also calls `loadModel()`, so a process death can no longer leave the
  app stuck on “Waking up”.
- The pending-capture/MediaStore recovery path is gone; `KeepAliveService` now only covers the
  photo/file pickers and stops on timeout.
- Imported photos are scaled to 1024 px and saved off the main thread.

## If the key is lost

The only alternatives are a fresh install under a new key (uninstall → chats and the model are
lost, then re-download) or keeping the verified **Jonaki AI Dev** build (`com.offlinegemma.app.dev`)
as the fixed build. See `KNOWLEDGE.md` §11.
