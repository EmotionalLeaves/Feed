## 6.26 — 2026-11-21
Data saver on mobile data

- **New setting, Data saver on mobile data** (Settings, under Data saver): videos are capped at 480p while you're on mobile data and go back to full quality on Wi-Fi, switching by itself as the phone moves between them.

## 6.25.2 — 2026-11-21
Porntrex change undone

- Undid 6.25.1's HTTP/1.1 change for Porntrex: the device check showed it didn't get past the site's bot check. Porntrex still works whenever the check lets the app through, and its videos still turn up from the other sites that carry them.

## 6.25.1 — 2026-11-21
Porntrex over HTTP/1.1

- **Porntrex is asked over HTTP/1.1:** its bot check had been turning the app away while letting the same request through from a plain client.
