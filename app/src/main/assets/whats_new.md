## 4.45.1 — 2026-11-16
Smoother from the first minute

- Ahead-of-time profiles for Compose and the other libraries are now installed on sideloaded installs too (normally the Play Store does it), so the app is smoother from its first minutes of use. Cold start measured 0.84 s.

## 4.45.0 — 2026-11-16
A much faster APK

- **New download: app-optimized.** Each build now also produces an optimized APK (R8-shrunk and not debuggable). It starts about twice as fast (0.9 s against 1.7–2.3 s on the test emulator), scrolls more smoothly and is 13 MB instead of 34 MB. It uses the same app id and key as the APK you have, so it installs over it and keeps your library, taste and settings.
- Search filters: order by best match, longest or shortest.

## 4.44.1 — 2026-11-16
Sort search results

- Search filters (the sliders icon) have an **Order** choice: best match, longest first (full scenes), or shortest first (quick clips).
