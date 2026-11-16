## 4.45.3 — 2026-11-16
Clear the video cache

- **Settings → Storage → Video cache → Clear** empties the cache of recently watched videos. Saved posts kept offline aren't touched.

## 4.45.2 — 2026-11-16
App code compiled ahead of time

- A baseline profile compiles the app's own code ahead of time at install. Cold start measured 0.79 s on the test emulator (it was 1.7–2.3 s with the old debug APK).

## 4.45.1 — 2026-11-16
Smoother from the first minute

- Ahead-of-time profiles for Compose and the other libraries are now installed on sideloaded installs too (normally the Play Store does it), so the app is smoother from its first minutes of use. Cold start measured 0.84 s.
