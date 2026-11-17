## 4.54.0 — 2026-11-16
AI filter, blocked-site fix, slimmer tab bar

- New: Hide AI-generated art, on by default (Settings → Feed shows). It catches Stable Diffusion, NovelAI, Midjourney and similar posts by their tags and titles, and skips AI-only boards and communities. This covers search and tag pages too.
- New: Settings → Privacy → Blocked where you are. The app now notices when a site shuts out your state or country, as RedGIFs and the Pornhub family do in states with age-verification laws, names the blocked sites, and can route just those sites through a proxy. You can use Orbot (Tor, free) or your own HTTP or SOCKS5 proxy, and everything else stays direct. The feed tells you once when a site turns out to be blocked.
- The tab bar is slimmer, with no pill and no solid slab. On the feed, videos run to the bottom edge under a soft fade, with captions and buttons kept above the bar. Turn a video sideways and the bar steps out of the way.

## 4.53.0 — 2026-11-16
PornHat

- New source: PornHat, a 16th tube site. It has new uploads, top rated and search, with each video's model or studio as its creator, its length and when it went up. Videos play in up to 720p, and it can be turned off in Sources → Tube sites.
- Checked a dozen other tube sites; the ones that block apps, are region-locked, or draw their pages by script are listed in SOURCES.md.

## 4.52.0 — 2026-11-16
Grid view for tags and creators

- New: tag and creator pages have a grid button at the top. You see everything loaded as tiles, and more loads as you scroll; tap one to watch from there. Back returns to the feed where you were.
