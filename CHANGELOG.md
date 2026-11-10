# Changelog

## 3.25.0 — 2026-11-10

Round 8: see what is paused.

- **Sources shows which sites are paused right now** (not answering, rate-limiting, or files not loading) under each source. They resume on their own after about 10 minutes.

## 3.24.0 — 2026-11-09

Round 7: search is tested end to end too.

- **New end-to-end test:** search a popular term against the live sources, get results, open them in the viewer, save a post, and swipe on through the results.

## 3.23.0 — 2026-11-09

Round 6: the build is now tested on what the app is for.

- **New end-to-end test on every build:** on a real emulator with a real network, the feed must show a post from the built-in sources within a minute, and keep showing posts through eight swipes. Before, the automated test only checked that screens opened; a build where nothing loaded would still pass.

## 3.22.0 — 2026-11-09

Round 5: sites that are down stop costing time.

- **A site that's down or rate-limiting is left alone for 10 minutes** after four failures in a row, then tried again. Checked live: Danbooru was in maintenance (every one of its ~500 feeds answering 503) and Reddit answers 429 after a few quick requests; before, part of every fetch round kept going to them.
- **No more 20-second sleeps on network threads.** A 429/503 used to be retried twice with waits of up to 10 s each, on the same threads video and images use. Now it's one retry, only when the server asks for 2 s or less.

## 3.21.0 — 2026-11-09

Round 4: every fetch round spans many sites.

- **No more bursts at one site.** With hundreds of feeds on rule34.xxx and gelbooru, a random pick of feeds often sent several requests to the same site at once, which sites answer with 403/429, and filled the round with one site's posts. Each round now asks any one board (or Mastodon server) for at most two feeds, so a round covers many sites: fewer refusals and a more varied pool.

## 3.20.0 — 2026-11-09

Round 3: source check report.

- **Check sources** now ends with a summary ("7 of 9 sources working from this phone") and a **Copy report** button, so the exact per-source results can be pasted into a bug report.

## 3.19.0 — 2026-11-09

Round 2: instant start.

- **The feed opens with posts already there.** Posts that were fetched and ranked last session but never shown are kept (encrypted, up to 36, for up to 6 hours) and shown immediately on the next launch, while fresh ones load behind them. Before, every launch waited on the network for its first screen. RedGIFs clips aren't kept because their links expire.

## 3.18.0 — 2026-11-09

Round 1: pruning no longer switches off working feeds.

- **Feeds wrongly marked dead are restored.** Earlier versions saw rule34.xxx, gelbooru, realbooru and tbib as empty (app bugs) and marked hundreds of their feeds dead for a week. That record is wiped once on update.
- **A 403 no longer counts against a feed.** Sites answer 403 at random when busy; only 404/410 (really gone) and repeatedly empty first pages count. Skipped feeds are retried after two days instead of seven.

## 3.17.0 — 2026-11-09

Sources fixed against the live sites (each claim below was checked by asking the real site, not assumed).

- **rule34.xxx returned nothing.** Without a key its API answers with a text message that parsed as "no posts", and the web-page fallback never ran. It now reads the web page directly.
- **gelbooru.com and realbooru.com returned nothing you could see.** Both changed their page layout (posts weren't found), and both only serve images and videos to requests naming the site as the referring page, so every file came back as a web page. New page reader for all three layouts; requests for their files now name the site.
- **tbib returned nothing.** Its API gives no file addresses, only a folder and file name; they're now built from those.
- **GIFs tagged "animated" were dropped as broken videos.** They show as GIFs.
- **Dead sites removed** (domain gone, shut down, or needs an account): rule34.us, dollbooru, and 13 Mastodon servers. Removed from saved settings too. Mastodon tag feeds now use five servers verified to answer.
- **Coomer:** its post list answers, but its file servers (n1–n4.coomer.st) did not respond at all when checked, which matches public reports of long outages. The app now tests one file before handing out Coomer posts, and says so instead of filling the feed with spinners.
- **A site whose files stop loading is rested** for 10 minutes after 3 failures in a row (while other sites load fine), instead of stalling on each of its posts.
- **Reddit:** blurry 320 px previews are no longer shown as posts (640 px minimum); fewer subreddits are asked at once, because Reddit rate-limits quickly.
- **Check sources** (Settings → Sources): tests every source from your phone and connection and shows, per source, whether it lists posts and whether their files load.
- Verified working as-is: RedGIFs, e621, e6ai, yande.re, Konachan, xbooru, hypnohub, Derpibooru, Furbooru. Danbooru was down for maintenance during the check; aibooru and Sankaku refused the test machine, which may differ on a phone.

## 3.16.0 — 2026-11-09

Round 10: Following feed.

- **Following actually fills.** It only filtered whatever the general feeds happened to bring in, and among thousands of feeds the creators and tags you follow rarely show up that way, so it could sit loading. It now searches every source for each followed creator and tag directly, paging deeper each time.

## 3.15.0 — 2026-11-09

Round 9: library growth.

- **+512 board feeds:** the body type × act pairings now also run on tbib and rule34.us (≈5,900 built-in feeds). Pairings with no posts are pruned automatically by feed health. Library version 7.

## 3.14.0 — 2026-11-09

Round 8: code health.

- Build is warning-free: replaced the last deprecated icons (volume on/off) with their auto-mirrored versions.

## 3.13.0 — 2026-11-09

Round 7: privacy and safety audit.

- Audited: every dialog and sheet sets the no-screenshot flag; nothing logs URLs or content; cleartext HTTP is off; the screen-awake flag drops when the app locks.
- **Minors filter** also catches plurals and unambiguous compounds it missed (`lolis`, `shotas`, `underaged`, `little_girl`, `young_girl`, `kindergarten`…), while ordinary tags such as `young_adult` still pass.

## 3.12.0 — 2026-11-09

Round 6: long-session performance.

- **No more full history re-read on every swipe.** The "already seen" filter re-queried every post in your history (up to 30 days, often tens of thousands of rows) and rebuilt a set from it each time you swiped, because each swipe adds a history row. It now loads history once and only watches the row count (to notice a clear or purge); new views are tracked in memory as they happen.

## 3.11.0 — 2026-11-09

Round 5: Library and Sources.

- **Library → Blocked shows hidden sources correctly** ("Hidden source", by name) instead of mislabelling them as blocked creators.
- **Followed creators are tappable** in Library → Following, opening a feed that searches every source for them (tags already did this).
- **Sources shows skipped feeds:** each source lists how many of its feeds are currently skipped as dead, and "Retry now" gives them all another try immediately.

## 3.10.0 — 2026-11-09

Round 4: playback.

- **Unplugging headphones never plays sound on the speaker.** Disconnecting headphones or Bluetooth mutes the feed (with a short notice) and keeps playing silently; before, unmuted audio moved straight to the speaker.
- **Muted feed doesn't stop your music.** While muted the player no longer takes audio focus, so a podcast or music keeps playing underneath. With sound on it takes focus normally, and a call pauses the video.
- **Screen stays awake** while the feed is on screen, so long videos don't get cut off by the screen timeout.

## 3.9.0 — 2026-11-09

Round 3: recommendations.

- **Short looping clips no longer hijack your taste.** Every automatic loop of a clip counted as a "rewatch", one of the strongest like signals, so a 3-second RedGIFs loop you sat on for 10 seconds read as a favourite and the feed drifted toward short loops. Automatic loops now count only for clips 15 s or longer; for short ones, time spent and completion carry the signal. Scrubbing back still counts as a rewatch.
- **Fair quality scores across sources.** RedGIFs like counts and board vote scores were on one scale, so every RedGIFs clip maxed out; each source now has its own scale. Sources with no votes (Reddit, Mastodon, Coomer) sit at the median instead of a hidden penalty.

## 3.8.0 — 2026-11-09

Round 2: search.

- **"Play all" no longer stops at the last result.** Opening search results plays them, then keeps going as an endless feed of the same search (it learns from what you watch, like any tag feed). With filters on, it plays just the filtered results, since the endless feed can't apply them.
- **RedGIFs search fetches three pages at once** (≈120 clips instead of 40).

## 3.7.0 — 2026-11-09

Round 1: dead feeds stop wasting requests; ~5,400 built-in feeds.

- **Dead-feed pruning.** With thousands of feeds, some will be dead (renamed subreddit, tag with no posts, text-only sub). Each one that answers "not found/forbidden" or whose first page has nothing showable gets a strike; at 3 strikes it's skipped for a week, then gets one more try. Strikes only count when other feeds in the same batch returned content, and network errors, timeouts, rate limits and server errors never count, so going offline or a site outage can't switch anything off.
- **+768 board feeds:** body type × act pairings (e.g. `milf paizuri`) on rule34.xxx, gelbooru and danbooru. Library version 6.

## 3.6.0 — 2026-11-09

Pass 3 of 3: faster images and network, ~4,600 built-in feeds.

- **Images load much faster.** Board posts used the original file, often a 5–20 MB PNG; stills now use the board's sample (~850–1500 px, a few hundred KB; Philomena's 1280 px rendition), and the original loads only when you pinch-zoom past 1.5× (the sample stays on screen meanwhile). GIFs and videos still use the original, since their "samples" are stills.
- **No more queuing behind playback.** OkHttp allowed only 5 requests per host at once, shared by video streaming, video preloads, images and API calls; raised to 16 per host / 96 total with a larger connection pool.
- **~4,600 built-in feeds** (was ~4,000): franchise feeds on every anime board (series with an adult main cast only), real-photo "video + act" feeds on realbooru, hypnohub, 1,124 RedGIFs feeds (more categories), 838 subreddits. Library version 5.
- Watch-time learning treats a post that turned out to be a clip as a video.

## 3.5.0 — 2026-11-09

Pass 2 of 3: landscape that stays put, hide a whole source, ~4,000 built-in feeds.

- **Landscape mode replaces the one-video fullscreen window.** "Rotate" turns the whole feed sideways and immersive, and it stays landscape while you swipe from post to post (videos and images), until you tap exit or press Back. Before, fullscreen was a separate window for one video that you couldn't swipe in and that ended on every new post.
- **Hide a source from any post.** The ⋯ menu has "Hide r/…" / "Hide Gelbooru" / "Hide RedGIFs": never show posts from that subreddit, board site or server again, without blocking the topic itself (hiding r/anal doesn't hide anal content elsewhere). Hidden sources are listed under Settings → Blocklist and can be unhidden.
- **~4,000 built-in feeds** (was ~3,100): "animated + act" board feeds on every anime board (short looping clips: the most TikTok-like board content), 973 RedGIFs category and pairing feeds (was 523), 768 subreddits (was 644). Merged into existing installs (library version 4).
- Mastodon search asks the 24 busiest servers instead of all ~70, which mostly returned the same federated posts.

## 3.4.0 — 2026-11-09

Pass 1 of 3: playback fixes, Coomer back online, ~3,100 built-in feeds.

- **"One-frame videos" fixed (three causes).** (1) Reddit posts were built from the feed's ~140 px `media:thumbnail` because the escaped post HTML was never decoded: images were blurry stills and every v.redd.it / RedGIFs post was a single frame. Reddit now plays v.redd.it as HLS (with sound), resolves RedGIFs links through the RedGIFs API, turns imgur `.gifv`/`.gif` into MP4, and shows full-size `i.redd.it` images. (2) A post listed as an image whose file is really MP4/WebM was drawn by the image loader's video-frame decoder — one still frame. Posts are now rendered by their actual file type. (3) A "video" whose only file is a still is skipped instead of shown.
- **Coomer works again.** coomer.su stopped resolving in mid-2025; the site moved to coomer.st. Default and saved settings are migrated, old domains are mapped automatically, and the API request uses the header it now expects.
- **Faster.** Coomer video thumbnails pointed at the video file, so preloading thumbnails downloaded whole clips several posts ahead; the duplicate-checker did the same. Neither touches video files now. The next 3 posts are resolved ahead of time (board post pages, RedGIFs lookups), full-size images are pre-warmed, the first-screen duplicate check waits 0.4 s instead of 2 s, and dead clips are skipped after 12 s instead of 20 s.
- **No more stalls after a short batch.** The feed keeps loading until 10 posts are buffered ahead instead of waiting for a swipe.
- **Reddit pages deep.** Subreddits now page with `after` (50 per page, up to 10 pages per pass) instead of stopping at 25 posts; search results carry their own subreddit.
- **~3,100 built-in feeds** (was ~1,000): 644 subreddits, 1,673 image-board feeds (real tag vocabularies × rule34.xxx, gelbooru, tbib, xbooru, rule34.us, danbooru, aibooru, realbooru, e621, e6ai, yande.re, konachan), 266 Mastodon tag feeds, 523 RedGIFs category and pairing feeds (was 24). Merged into existing installs by a settings migration. Nothing age-adjacent is generated.
- **Safety:** the minors filter also blocks `high_school`/`jailbait`-type tags and any stated age under 18 in titles ("16yo", "15 years old").

## 3.3.0 — 2026-11-08

~320 more feeds, and existing installs get them too.

- **Reddit:** ~185 more subreddits (amateur/international, body types, acts, fetish, trans/gay/lesbian, hentai/game, categories). Each subreddit is its own origin, so this also widens the variety the spacer has to work with.
- **Boorus:** ~95 more tag feeds across the reliable no-key hosts (rule34.xxx, gelbooru, e621, danbooru, realbooru, yande.re, konachan, tbib, xbooru, aibooru, e6ai), plus a new host — **rule34.us** (gelbooru engine), registered in `BooruFamily`.
- **Mastodon:** ~40 more tag timelines on the big instances (mastodon.social, mstdn.jp, lewd.town, yiff.social, femdom.social, and a few more).
- **Reaches existing installs:** a v2 settings migration merges the grown default library into saved settings — your own entries stay first, defaults are appended and de-duplicated, and SFW-only hosts are still filtered. Before this, new defaults only showed up on a clean install.
- **Unchanged:** the always-on minors filter and the `pawoo.net`/`baraag.net` exclusion stay; nothing age-adjacent or incest-themed was added.

## 3.2.1 — 2026-11-07

No more runs of one source on screen.

- **Root cause:** the spacing rules can only interleave what's in the candidate pool. On a cold start, or any moment when just one source has answered, the pool is briefly all one origin, so the spacer had no choice but to emit it back-to-back — a screen full of one subreddit/site.
- **Fix:** while any source might still add a different origin, a batch now stops short instead of emitting a forced run; the feed fills the rest on the next load, once varied candidates have arrived (`Spread.order` / `Ranker.rank` gain an `allowForcedRun` flag, wired to whether variety is still coming). A single origin is only ever run when it's genuinely all there is (every source exhausted, or the pool is one origin and nothing is loading — e.g. a lone image board).
- **Unchanged:** the non-porn filter (board safe ratings, unmarked Mastodon media, SFW-only sites) and the always-on minors filter stay exactly as they were; no other content is filtered.
- Tests: the single-origin case now asserts no on-screen run; added coverage for cold-start hold-back and the exhausted-source fallback.

## 1.1.0 — 2026-11-05

More sources and a faster way to add them.

- **New adapters:** Direct links, Reddit (public JSON), Mastodon/Fediverse (public + tag timelines), Booru (Danbooru/e621/moebooru-style `posts.json`), and a **Generic JSON** adapter that maps any JSON endpoint via user-defined field paths.
- **Quick-add UI:** Settings → Sources now has a type picker + inline fields for every source type, with a combined list of everything added and one-tap delete.
- **Refactor:** pure parsers extracted to `source/parse/` (`MediaTypes`, `JsonPath`, and the per-source parsers) and a shared `source/net/` (`HttpJson`, `CachingAdapter`). 10 new unit tests (JsonPath + the four parsers); 29 tests total.
- No site-specific scrapers: sources without a feed/API are left to the Direct and JSON adapters (see SOURCES.md).

## 1.0.0 — 2026-11-05

Initial build covering all seven phases of the spec. Written in a single pass and committed as one commit.

- **Phase 1, Skeleton:** Gradle/Hilt/Compose project, Room + SQLCipher with a Keystore-wrapped key, PIN + biometric gate, `FLAG_SECURE`, vertical pager.
- **Phase 2, Playback:** Media3 player pool (3 instances), LRU `SimpleCache`, preloading of the next 2 items, tap/double-tap/long-press gestures, overlay, remembered mute.
- **Phase 3, Sources:** `SourceAdapter` + `SourceRegistry` (enable/priority), RSS/Atom adapter, local-files (SAF) adapter, API adapter with OAuth PKCE (target service pending owner choice, see SOURCES.md), and per-source error messages instead of crashes.
- **Phase 4, Safety filter:** blocklist applied at ingest and live, plus block creator/tag, not-interested, report link and blocklist editor.
- **Phase 5, Recommendations & library:** signal log, decaying tag/creator affinities, ε-greedy ranker with freshness and diversity, cross-source dedup (title + duration + dHash), Saved/Liked/History/Following/Blocked screens, reset.
- **Phase 6, Search & settings:** parallel fan-out search (2.5 s per-source deadline) with source/type/duration chips; full settings screen.
- **Phase 7, Polish:** disguised launcher aliases, three-finger panic gesture, encrypted settings export/import, R8 rules, release signing from `local.properties`, unit tests.
