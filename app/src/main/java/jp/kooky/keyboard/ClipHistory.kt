package jp.kooky.keyboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// コピーした文の履歴（Gboardのクリップボードのようなもの）
// 端末の中（このアプリの設定ファイル）にだけ保存する。固定したものは消えずに残る
class ClipHistory(context: Context) {

    data class Item(val text: String, val pinned: Boolean)

    private val prefs = context.getSharedPreferences("kooky_keyboard", Context.MODE_PRIVATE)

    // 固定していないものは新しい順に最大この数まで
    private val max = 30

    fun load(): List<Item> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Item(o.getString("t"), o.optBoolean("p", false))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(list: List<Item>) {
        val a = JSONArray()
        for (i in list) a.put(JSONObject().put("t", i.text).put("p", i.pinned))
        prefs.edit().putString(KEY, a.toString()).apply()
    }

    // 並び：固定したもの（新しい順）→ それ以外（新しい順）
    private fun ordered(list: List<Item>): List<Item> {
        val pinned = list.filter { it.pinned }
        val rest = list.filter { !it.pinned }.take(max)
        return pinned + rest
    }

    // 追加。同じ文がすでにあれば先頭に移す（固定はそのまま）
    fun add(text: String) {
        if (text.isBlank()) return
        val list = load()
        val old = list.firstOrNull { it.text == text }
        if (old != null && list.indexOf(old) == (if (old.pinned) 0 else list.indexOfFirst { !it.pinned })) return
        val rest = list.filter { it.text != text }
        save(ordered(listOf(Item(text, old?.pinned == true)) + rest))
    }

    fun remove(text: String) = save(load().filter { it.text != text })

    fun togglePin(text: String) = save(ordered(load().map { if (it.text == text) it.copy(pinned = !it.pinned) else it }))

    // 固定していないものだけ全部消す
    fun clearUnpinned() = save(load().filter { it.pinned })

    companion object {
        const val KEY = "clip_hist"
    }
}
