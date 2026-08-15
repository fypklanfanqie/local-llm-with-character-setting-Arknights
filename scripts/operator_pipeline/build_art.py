#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Rebuild art_map with REAL PNG verification (GET + magic bytes + cookie) for e1/e2/skin1-6."""
import json, urllib.request, http.cookiejar, concurrent.futures, time, io

ops = json.load(open("C:/ak_work/operators.json", encoding="utf-8"))
names = [o["id"] for o in ops]

# --- cookie warm ---
cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
opener.addheaders = [("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"),
                     ("Referer", "https://prts.wiki/")]
try:
    r = opener.open("https://media.prts.wiki/3/3f/%E7%AB%8B%E7%BB%98_%E9%98%BF%E7%B1%B3%E5%A8%85_2.png", timeout=25)
    r.read(8); r.close()
except Exception as e:
    print("warm fail:", e)
cookie_str = "; ".join(f"{c.name}={c.value}" for c in cj)

def url_for(fn):
    from urllib.parse import quote
    h = __import__("hashlib").md5(fn.encode("utf-8")).hexdigest()
    return f"https://media.prts.wiki/{h[0]}/{h[:2]}/{quote(fn)}"

def is_png(u):
    try:
        req = urllib.request.Request(u, headers={
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36",
            "Referer": "https://prts.wiki/", "Cookie": cookie_str})
        with urllib.request.urlopen(req, timeout=25) as r:
            return r.read(8)[:4] == b"\x89PNG"
    except Exception:
        return False

def probe(name):
    cands = {}
    for suffix in ["_1", "_2"] + [f"_skin{i}" for i in range(1, 7)]:
        u = url_for(f"立绘_{name}{suffix}.png")
        if is_png(u):
            cands[suffix] = u
    return name, cands

start = time.time()
results = {}
with concurrent.futures.ThreadPoolExecutor(max_workers=10) as ex:
    futs = {ex.submit(probe, n): n for n in names}
    done = 0
    for fut in concurrent.futures.as_completed(futs):
        n, c = fut.result(); results[n] = c; done += 1
        if done % 50 == 0:
            print(f"  {done}/{len(names)} ({time.time()-start:.0f}s)")

# build map
art = {}
for n, c in results.items():
    e1, e2 = c.get("_1"), c.get("_2")
    skins = sorted([k for k in c if k.startswith("_skin")], key=lambda k: int(k[5:]), reverse=True)
    skin = c.get(skins[0]) if skins else None
    art[n] = {"e1": e1, "e2": e2, "skin": skin,
              "picture": e2 or e1 or "",
              "selection": skin or e1 or e2 or ""}

ok_pic = sum(1 for a in art.values() if a["picture"])
ok_sel = sum(1 for a in art.values() if a["selection"])
none = [n for n, a in art.items() if not a["picture"] and not a["selection"]]
print(f"picture OK={ok_pic}/364 | selection OK={ok_sel}/364 | 完全无图={len(none)} -> {none}")
io.open("C:/ak_work/art_map.json", "w", encoding="utf-8").write(json.dumps(art, ensure_ascii=False, indent=1))
print("art_map.json rebuilt in", round(time.time()-start), "s")
