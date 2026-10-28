package jp.kooky.keyboard

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel

// 変換候補（word を確定すると、読みの先頭 len 文字ぶんを置き換える）
// segs：文章変換のときの区切り（読み→単語）。確定時にそれぞれ学習する
class Cand(val word: String, val len: Int, val segs: List<Pair<String, String>>? = null)

// 辞書の1件（左品詞ID・右品詞ID・コスト）
class Entry(val w: String, val l: Int, val r: Int, val c: Int)

// 文章変換エンジン
// Mozc OSS辞書の「単語コスト」と「品詞の接続コスト」で、読みを最もそれらしい単語の並びに区切る
class Dict(private val context: Context) {

    companion object {
        const val UNK_POS = 1851      // 名詞,一般（辞書にない文字・登録単語に使う品詞）
        const val UNK_COST = 12000    // 辞書にない1文字の重さ
        const val USER_COST = 2000    // 登録単語は優先
        const val COMMON_COST = 5000  // よく使う言葉（英語表記など）
        const val LEARN_BONUS = 3000  // 一度選んだ変換は軽くする
        const val MAX_WORD = 12       // 1語の読みの最大文字数
    }

    @Volatile
    private var db: SQLiteDatabase? = null

    @Volatile
    private var conn: ShortBuffer? = null
    private var connN = 0

    @Volatile
    private var preparing = false

    private val learnDb = LearnDb(context)

    // 学習（読み→単語→回数）と登録単語（読み→単語）はメモリに持つ
    private val learned = HashMap<String, HashMap<String, Int>>()
    private val user = HashMap<String, MutableList<String>>()

    // よく使う言葉の一覧（assets/common.tsv：読み<TAB>単語）
    private val common = HashMap<String, MutableList<String>>()
    private var userVer = -1

    // 辞書を引いた結果のキャッシュ
    private val cache = object : LinkedHashMap<String, List<Entry>>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Entry>>?): Boolean = size > 4000
    }

    val ready: Boolean get() = db != null && conn != null

    // 初回（とアプリ更新後）に assets の辞書と接続コスト表を端末内にコピーして開く
    fun prepare() {
        if (ready || preparing) return
        preparing = true
        Thread {
            try {
                val dir = context.noBackupFilesDir
                val dictFile = File(dir, "dict.db")
                val connFile = File(dir, "conn.bin")
                val stamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
                val prefs = context.getSharedPreferences("kooky_keyboard", Context.MODE_PRIVATE)
                if (!dictFile.exists() || !connFile.exists() || prefs.getLong("dict_stamp", 0L) != stamp) {
                    copyAsset("dict.db", dictFile)
                    copyAsset("conn.bin", connFile)
                    prefs.edit().putLong("dict_stamp", stamp).apply()
                }
                loadMemory()
                // 接続コスト表はメモリに読み込まず、ファイルを直接参照する
                RandomAccessFile(connFile, "r").use { raf ->
                    val buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                    buf.order(ByteOrder.LITTLE_ENDIAN)
                    connN = buf.getInt(0)
                    buf.position(4)
                    conn = buf.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                }
                db = SQLiteDatabase.openDatabase(
                    dictFile.path, null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
                )
            } catch (e: Exception) {
                db = null
                conn = null
            } finally {
                preparing = false
            }
        }.start()
    }

    private fun copyAsset(name: String, dest: File) {
        val tmp = File(dest.parentFile, "$name.tmp")
        context.assets.open(name).use { input ->
            tmp.outputStream().use { out -> input.copyTo(out, 1 shl 16) }
        }
        if (dest.exists()) dest.delete()
        tmp.renameTo(dest)
    }

    private fun loadCommon() {
        if (common.isNotEmpty()) return
        try {
            context.assets.open("common.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isBlank() || line.startsWith("#")) continue
                    val p = line.split("\t")
                    if (p.size < 2) continue
                    val r = p[0].trim()
                    val w = p[1].trim()
                    if (r.isNotEmpty() && w.isNotEmpty()) synchronized(common) { common.getOrPut(r) { ArrayList() }.add(w) }
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun loadMemory() {
        loadCommon()
        synchronized(learned) {
            learned.clear()
            for ((r, w, n) in learnDb.allLearned()) learned.getOrPut(r) { HashMap() }[w] = n
        }
        reloadUser()
    }

    // 単語登録・学習リセットが設定画面で行われたら読み直す（変更のたびに番号が上がる）
    fun reloadUserIfChanged() {
        val v = context.getSharedPreferences("kooky_keyboard", Context.MODE_PRIVATE).getInt("udict_ver", 0)
        if (v != userVer) loadMemory()
    }

    // 設定画面から：変更を知らせる番号を上げる
    fun bumpVersion() {
        val prefs = context.getSharedPreferences("kooky_keyboard", Context.MODE_PRIVATE)
        prefs.edit().putInt("udict_ver", prefs.getInt("udict_ver", 0) + 1).apply()
    }

    fun userWords(): List<Pair<String, String>> = learnDb.allUser()

    fun addUserWord(r: String, w: String) {
        learnDb.addUser(r, w)
        bumpVersion()
    }

    fun removeUserWord(r: String, w: String) {
        learnDb.removeUser(r, w)
        bumpVersion()
    }

    private fun reloadUser() {
        val v = context.getSharedPreferences("kooky_keyboard", Context.MODE_PRIVATE).getInt("udict_ver", 0)
        synchronized(user) {
            user.clear()
            for ((r, w) in learnDb.allUser()) user.getOrPut(r) { ArrayList() }.add(w)
        }
        userVer = v
    }

    private fun connCost(prevRight: Int, nextLeft: Int): Int {
        val c = conn ?: return 0
        val i = prevRight * connN + nextLeft
        return if (i in 0 until c.limit()) c.get(i).toInt() else 5000
    }

    // 読みに一致する辞書の語（登録単語・学習を反映）
    private fun entries(r: String): List<Entry> {
        val raw = cache[r] ?: run {
            val out = ArrayList<Entry>()
            val d = db
            if (d != null) {
                d.rawQuery("SELECT w, l, rr, c FROM d WHERE r=?", arrayOf(r)).use { c ->
                    while (c.moveToNext()) out.add(Entry(c.getString(0), c.getInt(1), c.getInt(2), c.getInt(3)))
                }
            }
            cache[r] = out
            out
        }
        val lw = synchronized(learned) { learned[r]?.toMap() }
        val uw = synchronized(user) { user[r]?.toList() }
        val cw = synchronized(common) { common[r]?.toList() }
        if (lw == null && uw == null && cw == null) return raw

        val out = ArrayList<Entry>(raw.size + 4)
        val have = HashSet<String>()
        for (e in raw) {
            val bonus = if (lw?.containsKey(e.w) == true) LEARN_BONUS else 0
            out.add(Entry(e.w, e.l, e.r, maxOf(0, e.c - bonus)))
            have.add(e.w)
        }
        uw?.forEach { w -> if (have.add(w)) out.add(Entry(w, UNK_POS, UNK_POS, USER_COST)) }
        cw?.forEach { w ->
            val bonus = if (lw?.containsKey(w) == true) LEARN_BONUS else 0
            if (have.add(w)) out.add(Entry(w, UNK_POS, UNK_POS, maxOf(0, COMMON_COST - bonus)))
        }
        lw?.keys?.forEach { w -> if (have.add(w)) out.add(Entry(w, UNK_POS, UNK_POS, 4000)) }
        return out
    }

    private class Node(
        val w: String, val r: Int, val cost: Int,
        val start: Int, val end: Int
    ) {
        var total = Int.MAX_VALUE
        var prev: Node? = null
    }

    // 最小コストの区切り方を求める（ラティス＋最短経路）
    private fun bestPath(s: String): List<Node> {
        val n = s.length
        val ends = Array(n + 1) { ArrayList<Node>() }
        ends[0].add(Node("", 0, 0, 0, 0).apply { total = 0 })
        for (i in 0 until n) {
            if (ends[i].isEmpty()) continue
            for (j in i + 1..minOf(n, i + MAX_WORD)) {
                val sub = s.substring(i, j)
                var es = entries(sub)
                if (es.isEmpty() && j == i + 1) es = listOf(Entry(sub, UNK_POS, UNK_POS, UNK_COST))
                for (e in es) {
                    val node = Node(e.w, e.r, e.c, i, j)
                    for (p in ends[i]) {
                        if (p.total == Int.MAX_VALUE) continue
                        val t = p.total + connCost(p.r, e.l) + e.c
                        if (t < node.total) {
                            node.total = t
                            node.prev = p
                        }
                    }
                    if (node.prev != null) ends[j].add(node)
                }
            }
        }
        var best: Node? = null
        var bt = Int.MAX_VALUE
        for (p in ends[n]) {
            if (p.total == Int.MAX_VALUE) continue
            val t = p.total + connCost(p.r, 0)
            if (t < bt) {
                bt = t
                best = p
            }
        }
        val out = ArrayList<Node>()
        var node = best
        while (node != null && node.prev != null) {
            out.add(node)
            node = node.prev
        }
        out.reverse()
        return out
    }

    // 読みの続きを予測（「きしゅへ」→「機種変更」など）
    private fun predict(r: String, limit: Int): List<String> {
        val d = db ?: return emptyList()
        val out = ArrayList<String>()
        d.rawQuery(
            "SELECT w FROM d WHERE r>? AND r<? ORDER BY length(r), c LIMIT $limit",
            arrayOf(r, r + "￿")
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    // 1つの読みの単語候補（軽い順）
    private fun words(r: String, limit: Int): List<String> =
        entries(r).sortedBy { it.c }.map { it.w }.distinct().take(limit)

    fun candidates(reading: String): List<Cand> {
        val out = ArrayList<Cand>()
        val seen = HashSet<String>()
        fun add(w: String, len: Int, segs: List<Pair<String, String>>? = null) {
            if (w.isNotEmpty() && seen.add("$len\u0000$w")) out.add(Cand(w, len, segs))
        }
        val n = reading.length
        if (!ready) {
            add(reading, n)
            add(Kana.toKatakana(reading), n)
            return out
        }
        try {
            // 1. 前に選んだ変換（読み全体）
            synchronized(learned) {
                learned[reading]?.entries?.sortedByDescending { it.value }?.map { it.key }
            }?.forEach { add(it, n) }

            // 2. 文章として変換した結果
            val path = bestPath(reading)
            if (path.isNotEmpty()) {
                val segs = path.map { reading.substring(it.start, it.end) to it.w }
                add(path.joinToString("") { it.w }, n, if (path.size > 1) segs else null)
            }

            // 3. よく使う言葉（英語表記など：あまぞん → Amazon）
            synchronized(common) { common[reading]?.toList() }?.forEach { add(it, n) }

            // 4. 読み全体に一致する単語
            words(reading, 6).forEach { add(it, n) }

            // 4. ひらがな・カタカナ
            add(reading, n)
            add(Kana.toKatakana(reading), n)

            // 5. 予測（2文字以上）
            if (n >= 2) predict(reading, 6).forEach { add(it, n) }

            // 6. 先頭の区切りだけ別の候補（選ぶと残りは入力中のまま）
            if (path.size > 1) {
                val first = path[0].end
                val r1 = reading.substring(0, first)
                words(r1, 8).forEach { add(it, first) }
                add(r1, first)
                add(Kana.toKatakana(r1), first)
            }
            var found = 0
            var k = n - 1
            while (k >= 1 && found < 2) {
                if (path.isEmpty() || k != path[0].end) {
                    val ws = words(reading.substring(0, k), 3)
                    if (ws.isNotEmpty()) {
                        ws.forEach { add(it, k) }
                        found++
                    }
                }
                k--
            }
        } catch (e: Exception) {
            add(reading, n)
            add(Kana.toKatakana(reading), n)
        }
        return out
    }

    fun learn(reading: String, word: String, segs: List<Pair<String, String>>? = null) {
        try {
            learnOne(reading, word)
            segs?.forEach { (r, w) -> if (r != w) learnOne(r, w) }
        } catch (e: Exception) {
        }
    }

    private fun learnOne(r: String, w: String) {
        learnDb.add(r, w)
        synchronized(learned) {
            val m = learned.getOrPut(r) { HashMap() }
            m[w] = (m[w] ?: 0) + 1
        }
    }

    fun clearLearning() {
        try {
            learnDb.clear()
            synchronized(learned) { learned.clear() }
            bumpVersion()
        } catch (e: Exception) {
        }
    }
}

// 学習（選んだ変換）と単語登録を保存する
class LearnDb(context: Context) : SQLiteOpenHelper(context, "learn.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE l(r TEXT NOT NULL, w TEXT NOT NULL, n INTEGER NOT NULL, t INTEGER NOT NULL, PRIMARY KEY(r, w))")
        createUser(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createUser(db)
    }

    private fun createUser(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS u(r TEXT NOT NULL, w TEXT NOT NULL, t INTEGER NOT NULL, PRIMARY KEY(r, w))")
    }

    fun allLearned(): List<Triple<String, String, Int>> {
        val out = ArrayList<Triple<String, String, Int>>()
        readableDatabase.rawQuery("SELECT r, w, n FROM l", null).use { c ->
            while (c.moveToNext()) out.add(Triple(c.getString(0), c.getString(1), c.getInt(2)))
        }
        return out
    }

    fun add(r: String, w: String) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val st = db.compileStatement("UPDATE l SET n=n+1, t=? WHERE r=? AND w=?")
        st.bindLong(1, now)
        st.bindString(2, r)
        st.bindString(3, w)
        val updated = st.executeUpdateDelete()
        st.close()
        if (updated == 0) {
            db.execSQL("INSERT INTO l(r, w, n, t) VALUES(?, ?, 1, ?)", arrayOf<Any>(r, w, now))
        }
    }

    fun clear() {
        writableDatabase.execSQL("DELETE FROM l")
    }

    // ---------- 単語登録 ----------

    fun allUser(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        readableDatabase.rawQuery("SELECT r, w FROM u ORDER BY r, w", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0) to c.getString(1))
        }
        return out
    }

    fun addUser(r: String, w: String) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO u(r, w, t) VALUES(?, ?, ?)",
            arrayOf<Any>(r, w, System.currentTimeMillis())
        )
    }

    fun removeUser(r: String, w: String) {
        writableDatabase.execSQL("DELETE FROM u WHERE r=? AND w=?", arrayOf<Any>(r, w))
    }
}
