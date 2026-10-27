# Mozc OSS辞書 → 変換用データ
#   dict.db  : SQLite（読み r・単語 w・左品詞ID l・右品詞ID rr・コスト c）。読みでまとまって並ぶ
#   conn.bin : 品詞の接続コスト表（先頭4バイト=サイズN、続けて int16 LE を N*N 個。[右品詞(前の語) * N + 左品詞(次の語)]）
# 使い方: python3 tools/build_dict.py <dictionary_ossのフォルダ> <dict.db> <conn.bin>
import sys, sqlite3, os, glob, struct, array

MAX_COST = 9000      # これより珍しい語は入れない
MAX_READING = 12     # 読みの最大文字数
PER_READING = 12     # 1つの読みあたりの最大件数

src, out_db, out_conn = sys.argv[1], sys.argv[2], sys.argv[3]

best = {}
files = sorted(glob.glob(os.path.join(src, "dictionary0*.txt"))) + [os.path.join(src, "suffix.txt")]
for f in files:
    with open(f, encoding="utf-8") as fh:
        for line in fh:
            p = line.rstrip("\n").split("\t")
            if len(p) < 5:
                continue
            r, l, rr, cost, w = p[0], int(p[1]), int(p[2]), int(p[3]), p[4]
            if not r or not w or len(r) > MAX_READING or cost > MAX_COST:
                continue
            # 同じ単語でも品詞が違えば別に残す（助詞の「の」と名詞の「の」など）
            k = (r, w, l, rr)
            if k not in best or cost < best[k]:
                best[k] = cost

by = {}
for (r, w, l, rr), c in best.items():
    by.setdefault(r, []).append((c, w, l, rr))

rows = []
for r, lst in by.items():
    lst.sort()
    for c, w, l, rr in lst[:PER_READING]:
        rows.append((r, w, l, rr, c))
rows.sort()

if os.path.exists(out_db):
    os.remove(out_db)
db = sqlite3.connect(out_db)
db.execute(
    "CREATE TABLE d(r TEXT NOT NULL, w TEXT NOT NULL, l INTEGER NOT NULL, rr INTEGER NOT NULL, "
    "c INTEGER NOT NULL, PRIMARY KEY(r, w, l, rr)) WITHOUT ROWID"
)
db.executemany("INSERT INTO d VALUES(?,?,?,?,?)", rows)
db.commit()
db.execute("VACUUM")
db.close()

with open(os.path.join(src, "connection_single_column.txt")) as fh:
    n = int(fh.readline())
    a = array.array("h", (int(x) for x in fh))
assert len(a) == n * n
if sys.byteorder != "little":
    a.byteswap()
with open(out_conn, "wb") as fo:
    fo.write(struct.pack("<i", n))
    a.tofile(fo)

print("readings", len(by), "rows", len(rows), "db", os.path.getsize(out_db), "conn", os.path.getsize(out_conn))
