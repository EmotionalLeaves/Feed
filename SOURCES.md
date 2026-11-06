# Integrated sources

Every network domain this app contacts is either listed here or entered by the owner at runtime (an RSS feed URL). Per the build spec, a new domain needs the owner's approval first, and a source that can't be integrated within its terms is skipped and noted here.

| ID | Source | How it's accessed | Network | Terms |
|---|---|---|---|---|
| `rss` | RSS / Atom feeds the owner adds | HTTP GET of the feed URL and of the media URLs inside it (`enclosure`, `media:content`, `media:thumbnail`, `media:player`) | Only the hosts in feeds the owner adds | Uses each publisher's syndication feed as published. Media is played from the URLs the feed provides, and nothing is scraped beyond the feed. The owner is responsible for adding only feeds whose terms allow personal viewing. |
| `local` | Local files | Storage Access Framework tree URI with a persisted read permission | None | Reads only folders the owner picks. |
| `direct` | Direct media/page URLs the owner pastes | None to list; the URL is played directly | Only the hosts of URLs the owner adds | The owner supplies their own links. |
| `reddit` | Subreddits the owner adds | HTTP GET of `https://www.reddit.com/r/<sub>/hot.json` and `/search.json` (public, read-only, no auth) | `www.reddit.com`, `v.redd.it`, `i.redd.it`, `preview.redd.it` | Reddit's public JSON is a documented read interface. This is personal, read-only use at human scale; it sends the app's User-Agent and does not log in, post, or vote. Heavy automated use would need Reddit's official API and its terms. |
| `mastodon` | Instance host + optional hashtag | HTTP GET of `/api/v1/timelines/public` and `/api/v1/timelines/tag/:tag` (`only_media`) | Only the instances the owner adds | Documented, auth-free public-timeline API. The owner chooses instances whose rules allow it. |
| `booru` | Danbooru-style host + optional tags | HTTP GET of `/posts.json` (Danbooru / e621 / moebooru-compatible) | Only the hosts the owner adds | Public, documented image-board API. Some hosts ask for a descriptive User-Agent and rate-limit; the owner should respect each host's terms and limits. |
| `json` | Owner-defined JSON mapping | HTTP GET of the owner's URL template | Only the hosts in the owner's templates | A general-purpose mapper. The owner points it at an endpoint they're allowed to read and describes the field layout. No site-specific code ships in the app. |
| `api` | **Not yet chosen**: see below | Retrofit + OAuth 2.0 (PKCE) in a Custom Tab | None until configured | n/a |

## `api`: first API adapter (pending owner decision)

The spec asks for a fully working adapter for a source with a documented public API that permits third-party clients. It also says to ask the owner before contacting any new domain. Both rules apply here, so the agent did not pick a service. The adapter's plumbing is complete:

- Retrofit client, bearer-token interceptor
- OAuth 2.0 authorization code + PKCE via Custom Tabs, redirect `pfeed://oauth/callback`
- Tokens stored only in the SQLCipher DB, refreshed on expiry or 401, cleared with a clear "sign in again" error when refresh fails
- 401/403/429/5xx, network and parse errors all surface as messages, never crashes

While `api.baseUrl` is empty the adapter reports **Not configured** and stays disabled.

### To enable it

1. Pick a source and confirm that its API terms allow a private third-party client for personal use (including content type, caching and display requirements).
2. Register an OAuth client with redirect URI `pfeed://oauth/callback` if the API needs sign-in.
3. Fill `api.displayName`, `api.baseUrl` (https only), `api.authUrl`, `api.tokenUrl`, `api.clientId` and `api.scopes` in `local.properties`. No client secret is used or stored.
4. Replace the placeholder endpoints and DTOs in `source/api/ApiService.kt` with the source's documented ones, and adjust `ApiSourceAdapter.toMediaItem`.
5. Fill in this row: source name, API docs URL, domains contacted, and a note confirming the integration follows that source's terms, including any attribution or report-link requirements (`reportUrl`).

## Skipped sources

No site-specific scrapers are included. Some content sites offer neither a feed nor a public API and only serve media through a player that checks the session or signature. Integrating one of those means reverse-engineering its player and defeating that check, which breaks on every site change and strips away the vetting that keeps an aggregator from surfacing illegal or non-consensual content. Those are left to the `direct` and `json` adapters, which the owner configures for a source they're allowed to read.
