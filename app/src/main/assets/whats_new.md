## 6.25.1 — 2026-11-21
Porntrex over HTTP/1.1

- **Porntrex is asked over HTTP/1.1:** its bot check had been turning the app away while letting the same request through from a plain client.

## 6.25 — 2026-11-21
The TV keeps going

- **Casting keeps going:** when a video ends on the TV, the next video in the feed that can play there follows on its own. Ones that only play on their own site are passed over.

## 6.24.1 — 2026-11-21
Second chance after a refusal

- A site's list that's refused once (a bot check's "403", as Porntrex sometimes answers) is asked again a moment later, instead of leaving its videos out of that load.
