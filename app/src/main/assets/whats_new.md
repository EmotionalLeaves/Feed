## 6.18.3 — 2026-11-21
Picture cache back to full size

- The in-memory picture cache is back to its usual size (a quarter of the app's memory, at most 128 MB), so scrolling back doesn't reload thumbnails. The 6.18.2 cap had made it smaller than needed on most phones.

## 6.18.2 — 2026-11-21
Lighter on memory

- **The feed holds fewer decoded pictures in memory** (at most 128 MB), so long sessions are less likely to be closed by Android on phones with less memory.

## 6.18.1 — 2026-11-21
Videos with both

- **"Often with" on a performer's search card now finds the videos with both of them** in one tap, not just the other person.
- Combined searches ("name + name", "name + tag") also count the performers a site credits.
- Members-only qualities listed by some sites as a link to their login page are skipped, so the best quality that actually plays is picked.
