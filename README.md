# Private Feed

A private, sideloaded Android app that merges porn videos, images, GIFs and comics from many sites into one vertical swipe feed, with recommendations learned entirely on the phone. No accounts, no backend, no analytics, no notifications.

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

### Building on GitHub Actions

`.github/workflows/build.yml` runs on every push to `main` (or by hand from the **Actions** tab, using **Run workflow**). It runs the unit tests, builds the APK, runs lint, and attaches the APK to the run under **Artifacts**. Keep the repository **private**: artifacts are visible to anyone who can see the repo.

Without signing secrets, the workflow builds a debug-signed APK, which installs fine for personal use. To get a properly signed release APK, add these under **Settings → Secrets and variables → Actions**:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | output of `base64 -w0 pfeed.jks` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | e.g. `pfeed` |
| `KEY_PASSWORD` | key password |

Optional `API_DISPLAY_NAME`, `API_BASE_URL`, `API_AUTH_URL`, `API_TOKEN_URL`, `API_CLIENT_ID` and `API_SCOPES` secrets configure the API adapter (see SOURCES.md).

### Dependency versions

All versions are pinned in `gradle/libs.versions.toml`. If a newer AGP or Android Studio asks you to upgrade, bump them there. For example, AGP 9 has built-in Kotlin, which means removing the `kotlin-android` plugin line.

## Sideload

1. Copy the APK to the phone (USB, local network, etc.).
2. Open it from a file manager. If Android blocks it, go to **Settings → Apps → (your file manager) → Install unknown apps** and turn on **Allow from this source**.
3. Tap **Install**. You can turn the permission off again afterwards.

You can also run `adb install -r app/build/outputs/apk/release/app-release.apk`.

## Using it

Four tabs: **Feed**, **Search**, **Library**, **Settings**.

- **Feed** has three modes along the top: **For You** (learned from what you watch), **Following** (creators and tags you follow) and **Latest**. Swipe up for the next post; pull down on the first post to refresh.
- **Gestures:** tap to pause, double-tap to like, hold the right side of a video for 2×, press and hold for the menu (Not interested, hide a tag/creator/site, open or copy the link, report), three fingers anywhere to leave instantly. Pinch to zoom; zoomed in, one finger pans.
- **Comics** turn sideways page by page; tap the page counter for a strip of every page. Pages taller than the screen scroll, and a comic reopens on the page you were reading.
- **Search** asks every site at once and streams results in as each answers. Before you type, it shows what to **Continue** (videos and comics you left partway), your recent searches and topics.
- **Library:** Saved, Liked, History and Following. **Saved posts are kept on the phone** (downloaded on Wi-Fi into private storage) and play offline.
- The app lock (PIN, then biometrics) is optional: turn it on in **Settings → Privacy**.

## Sources

On by default, all filtered to porn only (non-porn posts are dropped) and through an always-on filter for anything involving minors:

| Source | What |
|---|---|
| **Tube sites** (15) | XVideos, XNXX, Pornhub, YouPorn, Tube8, xHamster, RedTube, Eporner, TNAFlix, Porntrex, YouJizz, Erome, Rule34Video, ThisVid, PornOne. Plays each site's own files directly (HLS or MP4, up to 1080p; 480p with Data saver). |
| **RedGIFs** | Clips and GIFs. |
| **Reddit** | Hundreds of NSFW subreddits (public RSS, paced to Reddit's limits). |
| **Image boards** | Gelbooru, Rule34, e621, Danbooru-style boards and more. |
| **Mastodon / Fediverse** | NSFW hashtags and instances. |
| **Coomer** | Creator posts. |
| **nhentai** and **Comic sites** | IMHentai, HentaiFox, HentaiEra, HentaiRox and 8muses comics; every gallery's tags are checked before it's shown. |
| **Derpibooru / Furbooru** | My Little Pony content stays rare unless you like, save or follow it. |

You can also add RSS feeds, direct links, any JSON API (see below) and a local folder. **Settings → Sources** has a health check that tests every source from your phone and connection.

### JSON API: map any source

The **Generic JSON adapter** turns any endpoint that returns JSON into a feed without writing code. You give it:

- **URL template**: for example `https://host/api/posts?tag={query}&page={cursor}`. `{query}` and `{cursor}` are filled in (left blank on a plain feed load).
- **Items path**: dotted path to the array of items, for example `data.children`.
- **Media path**: dotted path (relative to each item, after the optional **wrapper path**) to the playable URL, for example `url` or `data.url`.
- **More options**: wrapper path, id / title / creator / thumbnail / tags / page-URL paths, a **media type** hint (`auto` classifies by extension) and an optional **search URL template** using `{query}`.

## How For You learns

Every view is a signal: how long you stayed compared with *your own* usual pace, whether a video finished or was replayed, how far into a comic you read, likes, saves, follows, fast skips and "Not interested". Each one updates affinities for the post's tags, words, creator, site, format and length, which fade over time (14-day half-life). Your strongest interests are also searched for directly on every site, so rare tastes are found rather than waited for.

Ranking mixes taste, community score (as a percentile within each source), freshness and a little exploration, then spaces the feed so no site, subreddit or creator runs together. **Settings → Your taste** shows what it has learned; remove anything with ×, or start over.

## Architecture

```
dev.pfeed
├── domain/            pure Kotlin, unit-tested
│   ├── model/         MediaItem, Page, StreamInfo, FeedPrefs…
│   ├── filter/        Blocklist
│   └── reco/          TasteModel, Engagement, Ranker, Spread, Niche, Deduplicator, MinorsFilter
├── data/
│   ├── db/            Room entities/DAOs, encrypted by SQLCipher
│   ├── settings/      DataStore-backed AppSettings
│   └── repo/          FeedSession (fetch → filter → dedup → rank → space), Search, Library, Signals, OfflineStore
├── source/            SourceAdapter, SourceRegistry
│   ├── parse/         pure, unit-tested parsers (MediaTypes, JsonPath, Reddit/Booru/Mastodon/GenericJson)
│   ├── net/           HttpJson, CachingAdapter (fetch-many + in-memory paging base)
│   ├── tube/ comics/ nhentai/ redgifs/ reddit/ booru/ philomena/ mastodon/ kemono/ rss/ local/ direct/ generic/   the adapters
│   └── auth/          OAuth PKCE (Custom Tabs)
├── player/            MediaCache (LRU + offline), PlayerPool, Preloader, ResumePoints
├── security/          AppLockManager, SecretStore (PIN + Keystore-wrapped DB key), LauncherDisguise
├── work/              MaintenanceWorker (history purge, compaction)
└── ui/                Compose screens: feed, search, library, settings, lock
```

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
   - Add an entry to `SOURCES.md`, and run every item through `MinorsFilter`.

## Privacy & discretion

| Feature | Where |
|---|---|
| Optional app lock: PIN (PBKDF2-SHA256, salted; lockout after 5 wrong tries) then biometrics; locks again after a chosen time away | `AppLockManager`, `SecretStore` |
| `FLAG_SECURE` on the window, dialogs and sheets (no screenshots, blank in Recents) | `MainActivity`, `SecureDialog` |
| Encrypted database (SQLCipher, key wrapped by the Android Keystore) | `SecretStore.databasePassphrase` |
| Disguised home-screen icon and name | `LauncherDisguise` |
| Panic: three fingers anywhere locks and leaves | `AppRoot.panicGesture` |
| Private mode: no history, no search history, nothing learned | Settings → Privacy |
| Blur previews in Library and Search | Settings → Privacy |
| Encrypted DNS (1.1.1.1), automatic when a network blocks a site | `EncryptedDns` |
| Erase everything in one step | Settings → Privacy |
| Offline copies only in the app's private storage (never the gallery) | `OfflineStore` |
| No cloud backup, no notifications, no analytics | Manifest |

Known limits:

- Links opened in the browser ("Open on site") are outside the app's protection.
- The video cache and offline copies rely on Android's own storage encryption.
- Launcher disguise changes can take a few seconds to show on some launchers.

## Tests

- `app/src/test`: parsers for every site (from real page snippets), the minors filter, blocklist, dedup, spacing rules, the taste model and a simulation of the For You loop (converges on a taste, finds a rare one, follows a change of taste, judges comics by reading progress).
- `app/src/androidTest`: on a real emulator in CI, with the real network: posts display and keep scrolling, search across every source, saved posts are kept offline (including HLS), every source's health, the panic gesture, and screenshots of every screen (post pictures masked).
