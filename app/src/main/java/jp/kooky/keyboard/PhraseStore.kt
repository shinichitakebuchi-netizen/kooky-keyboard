package jp.kooky.keyboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Phrase(var title: String, var body: String) {
    // ボタンに出す名前（見出しが空なら本文の頭）
    fun label(): String = if (title.isNotBlank()) title else body.replace("\n", " ")
}

data class Category(var name: String, val phrases: MutableList<Phrase>)

class PhraseStore(context: Context) {
    private val prefs = context.getSharedPreferences("kooky_keyboard", Context.MODE_PRIVATE)

    fun load(): MutableList<Category> {
        val raw = prefs.getString(KEY, null) ?: return defaults()
        return try {
            fromJson(raw)
        } catch (e: Exception) {
            defaults()
        }
    }

    fun save(list: List<Category>) {
        prefs.edit().putString(KEY, toJson(list)).apply()
    }

    companion object {
        private const val KEY = "categories"

        fun toJson(list: List<Category>): String {
            val arr = JSONArray()
            for (c in list) {
                val items = JSONArray()
                for (p in c.phrases) {
                    items.put(JSONObject().put("t", p.title).put("b", p.body))
                }
                arr.put(JSONObject().put("name", c.name).put("items", items))
            }
            return arr.toString(2)
        }

        fun fromJson(raw: String): MutableList<Category> {
            val arr = JSONArray(raw)
            val out = mutableListOf<Category>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val items = o.optJSONArray("items") ?: JSONArray()
                val ps = mutableListOf<Phrase>()
                for (j in 0 until items.length()) {
                    val p = items.getJSONObject(j)
                    ps.add(Phrase(p.optString("t", ""), p.optString("b", "")))
                }
                out.add(Category(o.optString("name", "カテゴリ"), ps))
            }
            return out
        }

        // 初回だけ入るサンプル（設定画面で自由に書き換えOK）
        fun defaults(): MutableList<Category> = mutableListOf(
            Category(
                "接客", mutableListOf(
                    Phrase("来店お礼", "本日はご来店いただき、誠にありがとうございます。"),
                    Phrase("予約確認", "ご予約ありがとうございます。○月○日○時にお待ちしております。"),
                    Phrase("お待たせ", "大変お待たせいたしました。")
                )
            ),
            Category(
                "社内連絡", mutableListOf(
                    Phrase("お疲れさまです", "お疲れさまです。"),
                    Phrase("承知しました", "承知しました。対応いたします。"),
                    Phrase("シフト提出依頼", "お疲れさまです。来月のシフト希望を○日までに提出をお願いします。")
                )
            ),
            Category(
                "求人", mutableListOf(
                    Phrase("応募お礼", "この度はご応募いただきありがとうございます。面接日程についてご連絡いたします。")
                )
            )
        )
    }
}
