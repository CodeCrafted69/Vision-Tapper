# Vision Tapper

Floating overlay (via an Accessibility Service) that watches the screen and taps for you.

* **Tap an image** – select any part of your screen, crop it, and the app taps it whenever it appears.
* **Numbered images 1→n** – crop one numbered tile/button; every on-screen match is OCR'd and tapped in numerical order.
* **Numbers in an area 1→n** – drag a box over an area; any numbers found are tapped in order (1, 2, 3 …).

## Build with GitHub Actions
1. Create a GitHub repo and push this folder to `main`.
2. Open the **Actions** tab → *Build APK* (or run it with "Run workflow").
3. Download the `VisionTapper-debug-apk` artifact, unzip, install `app-debug.apk`.

## First run (Samsung One UI 9 / Android 17)
1. Open the app → *Open Accessibility settings* → Installed apps → Vision Tapper → enable.
2. If the toggle is greyed out: *App info* → ⋮ → **Allow restricted settings**, then retry.
3. A floating panel appears over every app: ◉ drag/collapse · ▶ start/stop · ＋ add target · ⚙ settings.

## Notes
* Apps that mark their screen secure (banking, some video apps) return a black screenshot – nothing can be detected there.
* For "Numbered images", lower the threshold (~70–75) because the number inside each tile differs from your crop.
* `targetSdk` is 36; bump to 37 when the Android 17 SDK is available in sdkmanager. The app uses no version-specific APIs beyond API 30.
