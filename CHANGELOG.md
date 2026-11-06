# Changelog

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
