package jp.kooky.keyboard

object Kana {

    // フリックの並び：[タップ, 左, 上, 右, 下]
    val A = arrayOf("あ", "い", "う", "え", "お")
    val KA = arrayOf("か", "き", "く", "け", "こ")
    val SA = arrayOf("さ", "し", "す", "せ", "そ")
    val TA = arrayOf("た", "ち", "つ", "て", "と")
    val NA = arrayOf("な", "に", "ぬ", "ね", "の")
    val HA = arrayOf("は", "ひ", "ふ", "へ", "ほ")
    val MA = arrayOf("ま", "み", "む", "め", "も")
    val YA = arrayOf("や", "「", "ゆ", "」", "よ")
    val RA = arrayOf("ら", "り", "る", "れ", "ろ")
    val WA = arrayOf("わ", "を", "ん", "ー", "")
    val PUNCT = arrayOf("、", "。", "？", "！", "…")

    // 「小゛゜」キーで順番に切り替わる組み合わせ
    private val cycles = listOf(
        "かが", "きぎ", "くぐ", "けげ", "こご",
        "さざ", "しじ", "すず", "せぜ", "そぞ",
        "ただ", "ちぢ", "つっづ", "てで", "とど",
        "はばぱ", "ひびぴ", "ふぶぷ", "へべぺ", "ほぼぽ",
        "あぁ", "いぃ", "うぅゔ", "えぇ", "おぉ",
        "やゃ", "ゆゅ", "よょ", "わゎ"
    )

    private val next: Map<Char, Char> = HashMap<Char, Char>().apply {
        for (c in cycles) {
            for (i in c.indices) put(c[i], c[(i + 1) % c.length])
        }
    }

    fun modify(ch: Char): Char? = next[ch]

    fun toKatakana(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            sb.append(if (ch in 'ぁ'..'ゖ') ch + 0x60 else ch)
        }
        return sb.toString()
    }
}
