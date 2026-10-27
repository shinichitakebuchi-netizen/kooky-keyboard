package jp.kooky.keyboard

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

// 変換候補（word を確定すると、読みの先頭 len 文字ぶんを置き換える）
class Cand(val word: String, val len: Int)

// 内蔵辞書（Mozc OSS辞書から作ったSQLite）
class Dict(private val context: Context) {

    @Volatile
    private var db: SQLiteDatabase? = null

    @Volatile
    private var preparing = false

    private val learnDb = LearnDb(context)

    val ready: Boolean get() = db != null

    // 初回（とアプリ更新後）に assets の辞書を端末内にコピーして開く
    fun prepare() {
        if (db != null || preparing) return
        preparing = true
        Thread {
            try {
                val dir = context.noBackupFilesDir
                val file = File(dir, "dict.db")
                val stamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
                val prefs = context.getSharedPreferences("kooky_keyboard", Context.MODE_PRIVATE)
                if (!file.exists() || prefs.getLong("dict_stamp", 0L) != stamp) {
                    val tmp = File(dir, "dict.db.tmp")
                    context.assets.open("dict.db").use { input ->
                        tmp.outputStream().use { out -> input.copyTo(out, 1 shl 16) }
                    }
                    if (file.exists()) file.delete()
                    tmp.renameTo(file)
                    prefs.edit().putLong("dict_stamp", stamp).apply()
                }
                db = SQLiteDatabase.openDatabase(
                    file.path, null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
                )
            } catch (e: Exception) {
                db = null
            } finally {
                preparing = false
            }
        }.start()
    }

    private fun exact(r: String, limit: Int): List<String> {
        val d = db ?: return emptyList()
        val out = ArrayList<String>()
        d.rawQuery("SELECT w FROM d WHERE r=? ORDER BY c LIMIT $limit", arrayOf(r)).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
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

    fun candidates(reading: String): List<Cand> {
        val out = ArrayList<Cand>()
        val seen = HashSet<String>()
        fun add(w: String, len: Int) {
            if (w.isNotEmpty() && seen.add("$len\u0000$w")) out.add(Cand(w, len))
        }
        val n = reading.length
        try {
            // 1. 学習した変換 → 2. 辞書の完全一致
            learnDb.lookup(reading).forEach { add(it, n) }
            exact(reading, 6).forEach { add(it, n) }
            // 3. ひらがな・カタカナ
            add(reading, n)
            add(Kana.toKatakana(reading), n)
            // 4. 予測（2文字以上のとき）
            if (n >= 2) predict(reading, 8).forEach { add(it, n) }
            // 5. 前の方だけ変換（「しょうちしました」→「承知」＋残り）
            var found = 0
            var k = n - 1
            while (k >= 1 && found < 2) {
                val pre = reading.substring(0, k)
                val ws = learnDb.lookup(pre) + exact(pre, 3)
                if (ws.isNotEmpty()) {
                    ws.forEach { add(it, k) }
                    found++
                }
                k--
            }
        } catch (e: Exception) {
            // 辞書エラー時はひらがな・カタカナだけ
            add(reading, n)
            add(Kana.toKatakana(reading), n)
        }
        return out
    }

    fun learn(reading: String, word: String) {
        try {
            learnDb.add(reading, word)
        } catch (e: Exception) {
        }
    }

    fun clearLearning() {
        try {
            learnDb.clear()
        } catch (e: Exception) {
        }
    }
}

// 選んだ変換を覚えて次から先頭に出す
class LearnDb(context: Context) : SQLiteOpenHelper(context, "learn.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE l(r TEXT NOT NULL, w TEXT NOT NULL, n INTEGER NOT NULL, t INTEGER NOT NULL, PRIMARY KEY(r, w))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun lookup(r: String): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT w FROM l WHERE r=? ORDER BY n DESC, t DESC LIMIT 5", arrayOf(r)
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
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
}
