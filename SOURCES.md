# Integrated sources

Every network domain this app contacts is either listed here or entered by the owner at runtime (an RSS feed URL). Per the build spec, a new domain needs the owner's approval first, and a source that can't be integrated within its terms is skipped and noted here.

| ID | Source | How it's accessed | Network | Terms |
|---|---|---|---|---|
| `rss` | RSS / Atom feeds the owner adds | HTTP GET of the feed URL and of the media URLs inside it (`enclosure`, `media:content`, `media:thumbnail`, `media:player`) | Only the hosts in feeds the owner adds | Uses each publisher's syndication feed as published. Media is played from the URLs the feed provides, and nothing is scraped beyond the feed. The owner is responsible for adding only feeds whose terms allow personal viewing. |
| `local` | Local files | Storage Access Framework tree URI with a persisted read permission | None | Reads only folders the owner picks. |
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

None so far.
