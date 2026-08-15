#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Compute operator art URLs via MediaWiki md5-hash path trick + HEAD verification."""
import json, hashlib, concurrent.futures, urllib.request, time, io

DATA = json.load(open("C:/ak_work/operators.json", encoding="utf-8"))

def url_for(fn):
    h = hashlib.md5(fn.encode("utf-8")).hexdigest()
    from urllib.parse import quote
    return f"https://media.prts.wiki/{h[0]}/{h[:2]}/{quote(fn)}"

def exists(url, timeout=15):
    try:
        req = urllib.request.Request(url, method="HEAD", headers={"User-Agent":"Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status == 200
    except Exception:
        return False

def probe(name):
    cands = {}
    # E2 / E1
    for suffix in ("_2", "_1"):
        fn = f"立绘_{name}{suffix}.png"
        u = url_for(fn)
        if exists(u):
            cands[suffix] = u
    # skins skin1..skin6 (highest existing = newest skin)
    for n in range(1, 7):
        fn = f"立绘_{name}_skin{n}.png"
        u = url_for(fn)
        if exists(u):
            cands[f"skin{n}"] = u
    return name, cands

names = [r["name"] for r in DATA]
print("probing", len(names), "operators...")
start = time.time()
results = {}
with concurrent.futures.ThreadPoolExecutor(max_workers=12) as ex:
    futs = {ex.submit(probe, n): n for n in names}
    done = 0
    for fut in concurrent.futures.as_completed(futs):
        n, cands = fut.result()
        results[n] = cands
        done += 1
        if done % 50 == 0:
            print(f"  {done}/{len(names)} ({time.time()-start:.0f}s)")

# stats
with_e2 = sum(1 for v in results.values() if "_2" in v)
with_e1 = sum(1 for v in results.values() if "_1" in v)
with_skin = sum(1 for v in results.values() if any(k.startswith("skin") for k in v))
none = {n for n,v in results.items() if not v}
print("with E2:", with_e2, "| with E1:", with_e1, "| with skin:", with_skin, "| none:", len(none))
if none:
    print("  no-art:", sorted(none)[:20])

art = {}
for n, cands in results.items():
    e2 = cands.get("_2")
    e1 = cands.get("_1")
    skins = sorted([k for k in cands if k.startswith("skin")],
                   key=lambda k: int(k[4:]), reverse=True)
    skin = cands.get(skins[0]) if skins else None
    art[n] = {"e2": e2, "e1": e1, "skin": skin,
              "picture": e2 or e1 or "",
              "selection": skin or e1 or ""}

io.open("C:/ak_work/art_map.json", "w", encoding="utf-8").write(
    json.dumps(art, ensure_ascii=False, indent=1))
print("wrote art_map.json in", round(time.time()-start), "s")
