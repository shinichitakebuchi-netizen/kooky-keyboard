import sys, sqlite3, os, glob, re
KATA = re.compile("[\u30a0-\u30ffー・]+")
src, out = sys.argv[1], sys.argv[2]
best = {}
for f in sorted(glob.glob(os.path.join(src, "dictionary0*.txt"))):
    with open(f, encoding="utf-8") as fh:
        for line in fh:
            p = line.rstrip("\n").split("\t")
            if len(p) < 5: continue
            r, cost, w = p[0], int(p[3]), p[4]
            if not r or not w or len(r) > 12 or cost > 9000: continue
            if KATA.fullmatch(w) or w == r: continue
            k = (r, w)
            if k not in best or cost < best[k]: best[k] = cost
# 読みごとにコスト順で上位だけ残す
by = {}
for (r, w), c in best.items(): by.setdefault(r, []).append((c, w))
if os.path.exists(out): os.remove(out)
db = sqlite3.connect(out)
db.execute("CREATE TABLE d(r TEXT NOT NULL, w TEXT NOT NULL, c INTEGER NOT NULL)")
rows = []
for r, lst in by.items():
    lst.sort()
    for c, w in lst[:6]: rows.append((r, w, c))
db.executemany("INSERT INTO d VALUES(?,?,?)", rows)
db.execute("CREATE INDEX i ON d(r)")
db.commit(); db.execute("VACUUM"); db.close()
print("readings", len(by), "rows", len(rows), "bytes", os.path.getsize(out))
