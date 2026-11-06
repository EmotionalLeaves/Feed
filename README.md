# Private Feed

A single-user, sideloaded Android app that merges video, image and GIF content from sources you choose into one vertical swipe feed, with recommendations computed entirely on the device. It has no accounts, no backend, no sharing, no analytics and no notifications.

- **Stack:** Kotlin, Jetpack Compose (Material 3), Hilt, Room + SQLCipher, Retrofit/OkHttp/kotlinx.serialization, Media3 ExoPlayer, Coil, WorkManager, AndroidX Biometric.
- **Min SDK 26 / target SDK 36.**

## Build

Requirements: JDK 17+, the Android SDK (platform 36 + build-tools), and internet access for the first Gradle sync.

```bash
cp local.properties.example local.properties   # set sdk.dir, signing, optional api.*
./gradlew testDebugUnitTest                     # ranking, dedup, blocklist tests
./gradlew lint                                  # must show 0 errors
./gradlew assembleRelease                       # → app/build/outputs/apk/release/app-release.apk
```

### Release signing

Create a keystore once and keep it outside the repo:

```bash
keytool -genkeypair -v -keystore ~/keys/pfeed.jks -alias pfeed -keyalg RSA -keysize 4096 -validity 10000
```

Then set `release.storeFile`, `release.storePassword`, `release.keyAlias` and `release.keyPassword` in `local.properties`. That file is git-ignored and should never be committed. If those keys are missing, `assembleRelease` produces an unsigned APK.

### Dependency versions

All versions are pinned in `gradle/libs.versions.toml`. If a newer AGP or Android Studio asks you to upgrade, bump them there. For example, AGP 9 has built-in Kotlin, which means removing the `kotlin-android` plugin line.

## Sideload

1. Copy `app-release.apk` to the phone (USB, local network, etc.).
2. Open it from a file manager. If Android blocks it, go to **Settings → Apps → (your file manager) → Install unknown apps** and turn on **Allow from this source**.
3. Tap **Install**. You can turn the permission off again afterwards.

You can also run `adb install -r app/build/outputs/apk/release/app-release.apk`.

## First run

1. **Create a PIN** (4–8 digits). After that, biometrics are offered if the device supports them.
2. Go to **Settings → Manage sources** and add RSS/Atom feed URLs and/or pick local folders.
3. Swipe the feed: **tap** to pause, **double-tap** to like, **long-press** for the menu (Save, Not interested, Follow, Block creator/tag, Open source page, Report).
4. **Panic:** a three-finger tap anywhere locks the app and jumps to the home screen.

## Architecture

```
dev.pfeed
├── domain/            pure Kotlin, unit-tested
│   ├── model/         MediaItem, Page, StreamInfo, FeedPrefs…
│   ├── filter/        Blocklist
│   └── reco/          AffinityModel (decay), Ranker (ε-greedy), Deduplicator, PerceptualHash
├── data/
│   ├── db/            Room entities/DAOs, encrypted by SQLCipher
│   ├── settings/      DataStore-backed AppSettings
│   └── repo/          FeedSession (fetch → blocklist → dedup → rank), Search, Library, Signals, Export
├── source/            SourceAdapter, SourceRegistry, rss/, local/, api/, auth/ (OAuth PKCE)
├── player/            MediaCache (LRU), PlayerPool (3 ExoPlayers), Preloader
├── security/          AppLockManager, SecretStore (PIN + Keystore-wrapped DB key), LauncherDisguise
├── work/              MaintenanceWorker (history purge, compaction)
└── ui/                Compose screens: feed, search, library, settings, lock
```

### Feed pipeline (`FeedSession.loadMore`)

1. Fetch the next page from every enabled adapter in parallel. Each adapter keeps its own cursor.
2. Drop items already shown, then apply the **blocklist**. The UI also re-filters live, so a new block takes effect immediately.
3. Apply the mode filter: Following or tag.
4. **Deduplicate** across sources using the normalized title, the duration and a 64-bit dHash of the thumbnail. Higher-priority sources win.
5. **Rank** (For You) or sort by date (Latest, Following, tag).

### Recommendations

Signals are logged locally in `signals`: watch ratio, completion, replays, likes, saves, fast skips (< 2 s), not-interested and follows. Each signal updates a per-tag and per-creator affinity score with exponential decay (half-life is configurable, default 14 days).

```
score = w_tag·tanh(tagAff/5) + w_creator·tanh(creatorAff/5) + w_fresh·e^(−age/7d) + w_div·sourceDiversity
```

Exploration is ε-greedy: by default 10% of slots are filled at random. You can change all the weights in **Settings → Recommendation weights**.

### Playback

- `PlayerPool` owns exactly three ExoPlayers. The pager composes the current page ±1 (`beyondViewportPageCount = 1`). Each video page acquires a player when it is composed and releases it when it is disposed, so decoder count and memory stay flat however far you swipe.
- **Preload:** item +1 is prepared and buffering in its pooled player. For item +2, the thumbnail and the first ~1.5 MB of the stream are written into the cache (`Preloader`).
- The cache is a `SimpleCache` with an LRU evictor (default 1 GB, configurable; a new size applies on next launch). It is bypassed for `content://` local files.

## Adding a SourceAdapter

1. Create a class in `source/<name>/` that implements `SourceAdapter`:

   ```kotlin
   @Singleton
   class MyAdapter @Inject constructor(private val http: OkHttpClient) : SourceAdapter {
       override val id = "my"                   // stable, unique, used in item keys
       override val displayName = "My Source"
       override val requiresAuth = false
       override suspend fun authenticate(ctx: AuthContext) = AuthResult.NotRequired
       override suspend fun fetchFeed(cursor: String?, prefs: FeedPrefs): Page<MediaItem> = TODO()
       override suspend fun search(query: String, cursor: String?): Page<MediaItem> = TODO()
       override suspend fun resolveStream(item: MediaItem): StreamInfo = TODO()
       override fun reportUrl(item: MediaItem): String? = item.pageUrl
   }
   ```

2. Register it in `di/SourceModule.kt` with a `@Binds @IntoSet` line.
3. Rules:
   - Throw `SourceException(id, "message", isAuthError = …)` for anything the user should see. The feed shows the message and keeps going with the other sources.
   - `cursor == null` means "refresh from the top". Return `nextCursor = null` at the end.
   - Return `StreamInfo.Embed(url)` only for an **official** embed player. It renders in the sandboxed WebView.
   - For OAuth, use `OAuthManager.authorize(ctx.activity, OAuthConfig(...))`, which runs PKCE in a Custom Tab with redirect `pfeed://oauth/callback`. Store tokens with `CredentialDao`, which lives in the encrypted DB.
   - Use only the source's documented API and stay within its terms. Get the owner's OK before contacting a new domain, and add an entry to `SOURCES.md`.

## Privacy & discretion

| Feature | Where |
|---|---|
| Lock on launch and on resume after timeout (default 30 s) | `AppLockManager` |
| PIN: PBKDF2-SHA256, 60k iterations, salted; 30 s lockout after 5 wrong tries | `SecretStore`, `AppLockManager` |
| `FLAG_SECURE` on the main window, dialogs and bottom sheets (no screenshots, blank in Recents) | `MainActivity`, `SecureDialog` |
| Encrypted DB with a random 256-bit key wrapped by an Android Keystore AES-GCM key | `SecretStore.databasePassphrase` |
| No cloud backup or device transfer | `allowBackup=false`, `data_extraction_rules.xml` |
| No notifications (`POST_NOTIFICATIONS` is not requested) | Manifest |
| Disguised launcher (Notes / Calculator) via `activity-alias` | `LauncherDisguise` |
| Panic gesture (three-finger tap) | `AppRoot.panicGesture` |
| Settings export: AES-256-GCM, PBKDF2 (210k), excludes PIN/tokens/history | `SettingsExporter` |

Known limits:

- Custom Tabs used for sign-in and "open source page" belong to the browser, so `FLAG_SECURE` doesn't cover them.
- The video cache in the app's private cache directory is not encrypted at rest.
- Launcher disguise changes can take a few seconds to show on some launchers.

## Tests

`app/src/test` covers `Blocklist`, `Deduplicator` (title/duration and perceptual-hash paths, cross-page state), `Ranker` (affinity, creator, freshness, diversity, ε-exploration, and the "20 likes raise tag share" acceptance scenario) and `AffinityModel` decay.
