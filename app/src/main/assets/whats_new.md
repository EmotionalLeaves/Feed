## 6.25.2 — 2026-11-21
Porntrex change undone

- Undid 6.25.1's HTTP/1.1 change for Porntrex: the device check showed it didn't get past the site's bot check. Porntrex still works whenever the check lets the app through, and its videos still turn up from the other sites that carry them.

## 6.25.1 — 2026-11-21
Porntrex over HTTP/1.1

- **Porntrex is asked over HTTP/1.1:** its bot check had been turning the app away while letting the same request through from a plain client.

## 6.25 — 2026-11-21
The TV keeps going

- **Casting keeps going:** when a video ends on the TV, the next video in the feed that can play there follows on its own. Ones that only play on their own site are passed over.
