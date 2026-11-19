"""Fetch a listing page and one video page from each candidate site, for writing parsers against."""
import os, re, sys, gzip, json, urllib.request, urllib.parse

UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
SITES = json.loads(os.environ.get("SITES_JSON", "{}"))
out = "probe-out"; os.makedirs(out, exist_ok=True)
report = []

def get(url, referer=None):
    h = {"User-Agent": UA, "Accept": "text/html,application/json,*/*", "Accept-Language": "en-US,en;q=0.9"}
    if referer: h["Referer"] = referer
    req = urllib.request.Request(url, headers=h)
    with urllib.request.urlopen(req, timeout=25) as r:
        data = r.read()
        if r.headers.get("Content-Encoding") == "gzip": data = gzip.decompress(data)
        return r.status, r.geturl(), data.decode("utf-8", "replace")

for name, cfg in SITES.items():
    for i, url in enumerate(cfg["lists"]):
        ref = None
        if isinstance(url, list): url, ref = url
        try:
            st, final, body = get(url, referer=ref)
            open(f"{out}/{name}-list{i}.html", "w").write(body)
            report.append(f"{name} list{i} {st} {final} {len(body)}")
            if i == 0:
                links = [m if m.startswith("http") else urllib.parse.urljoin(final, m) for m in re.findall(cfg["video"], body)]
                links = list(dict.fromkeys(links))
                report.append(f"{name} video links found: {len(links)} e.g. {links[:3]}")
                for j, v in enumerate(links[:2]):
                    try:
                        st2, f2, b2 = get(v, referer=final)
                        open(f"{out}/{name}-video{j}.html", "w").write(b2)
                        report.append(f"{name} video{j} {st2} {f2} {len(b2)}")
                    except Exception as e:
                        report.append(f"{name} video{j} ERROR {e}")
        except Exception as e:
            report.append(f"{name} list{i} ERROR {e}")
open(f"{out}/REPORT.txt", "w").write("\n".join(report))
print("\n".join(report))
