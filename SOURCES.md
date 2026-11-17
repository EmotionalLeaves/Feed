# Sources

Every network domain the app contacts is listed here, or is entered by you at runtime (an RSS feed, a direct link, a JSON endpoint, an extra board or instance).

All requests go through one OkHttp client. It sends a normal mobile browser's User-Agent and the site's own address as Referer. Encrypted DNS (1.1.1.1) is used automatically when a network blocks a name, and always when you turn it on. Every post from every source passes the always-on minors filter and the porn-only filter before it can appear.

| ID | Source | How it's read | Domains |
|---|---|---|---|
| `tube` | Tube sites (16) | Each site's own listing, search and video pages; the stream is taken from the page (HLS or MP4) and played directly, else the site's embed player. Rule34Video and ThisVid pages are read first so their tags can be checked. | xvideos.com, xnxx.com, pornhub.com, youporn.com, tube8.com, xhamster.com (+ xhamster2.com, xhamster.desi, xhamster19.com mirrors), redtube.com, eporner.com (official API), tnaflix.com, porntrex.com, youjizz.com, erome.com, rule34video.com, thisvid.com, pornone.com, pornhat.com (+ cdn.privatehost.com), and their CDNs |
| `redgifs` | RedGIFs | Public API with a temporary token | api.redgifs.com, media.redgifs.com |
| `reddit` | Subreddits | Public RSS listings and search, paced to one request every 6 s | www.reddit.com, i.redd.it, preview.redd.it |
| `booru` | Image boards | Each board's public API (Gelbooru, Danbooru, e621, Moebooru and compatible) | The boards in Sources (Gelbooru, Rule34, e621, Danbooru, Konachan, Yande.re, Hypnohub and more) |
| `philomena` | Derpibooru / Furbooru | Public API | derpibooru.org, furbooru.org |
| `mastodon` | Fediverse | Public and hashtag timelines (`only_media`) | The instances in Sources (pawoo.net and baraag.net are always excluded) |
| `kemono` | Coomer | Public API | coomer.st |
| `nhentai` | nhentai | Public API, paced | nhentai.net, its image servers |
| `comics` | Comic sites | Each site's listing, search and gallery pages; every gallery's own tags are read and checked | imhentai.xxx, hentaifox.com, hentaiera.com, hentairox.com, comics.8muses.com, and their image servers |
| `rss` | RSS / Atom | The feeds you add | Yours |
| `direct` | Direct links | The links you paste | Yours |
| `json` | Any JSON API | Your URL template and field mapping | Yours |
| `local` | Local folder | Storage Access Framework | None |
| `api` | OAuth API adapter | Plumbing only, off until configured (`api.*` in `local.properties`) | None until configured |

## Tried and skipped

- **SpankBang, Hanime, PornTube:** Cloudflare challenge pages, no listing without a browser.
- **AnyPorn, AlphaPorno:** answer 403 to every request. **SxyPrn:** region-blocked. **4tube, Fux:** blocked by state legislation where the checks run. **PornoXO:** Cloudflare challenge. **Txxx, HClips, VJav:** pages are drawn by script with no listing in the HTML. **XXXBunker:** a traffic-verification page. **Beeg:** its API answers, but needs more work to map.
- **Multporn:** its listing gives about three comics a page and its search doesn't answer.
- **Motherless:** the domain doesn't resolve, even over encrypted DNS.
- **Erome photo-only albums:** they carry no tags to check, so only Erome videos are used.
