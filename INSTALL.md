# Installing Jonaki (for internal testers)

Jonaki (জোনাকি, "firefly") is a chat app with its own AI (Google's Gemma). The AI runs **on your phone**:
your chats, photos and voice never leave it.

## What you need

- An Android phone with **Android 12 or newer**.
- **8 GB of memory (RAM)** works best. 4 GB phones work too, but answers come slowly.
- About **4 GB of free space** (the AI is 2.6 GB, plus room to work).
- **Wi-Fi** for the one-time download.

## Install it (about 2 minutes)

1. Get the file **`Jonaki-1.0.0.apk`** from the person who shared it (Drive, email, chat…).
2. Open the file on your phone. Your phone may say *"For your security, your phone is not allowed
   to install unknown apps from this source."* Tap **Settings**, turn on **Allow from this source**,
   then go back and tap **Install**.
   - Xiaomi / Redmi phones may show an extra warning page. Wait for the timer and tap **Install anyway**.
   - Google Play Protect may say it doesn't know this app. Tap **More details → Install anyway**.
     (This happens because the app isn't on the Play Store yet.)
3. Open **Jonaki**.

## First launch (about 5–10 minutes on Wi-Fi)

1. Tap **Get started**.
2. The app checks your memory and storage and shows **only the models this phone can run** — with
   how much memory each needs next to how much your phone has. Tap **Download**.
   A model that is too big for the phone is never offered, because running one can make the whole
   phone slow or unstable, not just this app.
3. You can leave the app; the download keeps going and shows in your notifications.
   If Wi-Fi drops, it carries on by itself later. It never uses mobile data unless you switch off **Wi-Fi only**.
4. When it's done, Gemma wakes up (about 20 seconds the first time) and you can chat.

If a model was already installed by an older version, the download screen skips itself and you go
straight to chatting. If that model turns out to be too large for the phone, the screen says so and
offers to remove it.

## Things to try

- **Ask anything**, e.g. *"Explain black holes with a demo"*. Tutor answers have live demos with sliders,
  follow-up question buttons, and 🧒 Simpler / 🔬 Go deeper / ❓ Quiz me / ✅ Check facts.
- **Tap +** to add a photo, take a picture, or add a PDF / Word / PowerPoint / Excel file.
  Add a screenshot of a coding problem and type *"solve it"*: the app also runs the code to test it.
- **Tap the sound-wave button** (bottom right, when the box is empty) for **talk mode**:
  just speak, pause, and Gemma answers out loud. Talk over Gemma to interrupt.
  Headphones work best.
- Open the **☰ menu** for your saved chats, **Settings** (voice, speech speed, theme, web search) and **About**.

## Privacy

Everything stays on the phone, except:
- the one-time model download, and
- **web search** (✅ Check facts, or when Gemma looks something up). The search words go to DuckDuckGo.
  You can switch web search off in **Settings**.

## Updating

Install the new `.apk` over the old one. Your chats and the downloaded AI are kept.

## Problems?

- **"Gemma could not start"**: close other apps and tap **Try again**. Restart the phone if it keeps happening.
- **Slow answers**: normal on 4 GB phones. Long chats are summarised automatically to keep going.
- **Uninstalling** removes the app, the AI (2.6 GB) and all chats.

---

### For the person who builds the app

- Release file: `mobile/app/build/outputs/apk/release/app-release.apk`
  (build with `gradlew assembleRelease` in the `mobile` folder). Rename it to `Jonaki-<version>.apk`.
- It is signed with the key in `C:\Users\monar\.android-keys\` (`offlinegemma.jks` + `offlinegemma.properties`).
  **Back up that folder somewhere safe and private.** Without it you can't ship updates that install over the old app.
  Never put it in git.
- Before each new release, raise `versionCode` and `versionName` in `app/build.gradle.kts`.
- Test builds (`assembleDebug`) are called **Jonaki Dev** and install next to the real app.
