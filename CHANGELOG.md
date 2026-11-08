# Changelog

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
