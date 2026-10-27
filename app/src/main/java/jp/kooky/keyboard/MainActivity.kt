package jp.kooky.keyboard

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var store: PhraseStore
    private var cats: MutableList<Category> = mutableListOf()
    private var current = 0

    private lateinit var statusText: TextView
    private lateinit var tabsRow: LinearLayout
    private lateinit var listBox: LinearLayout

    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PhraseStore(this)
        cats = store.load()
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(48))
        }
        scroll.addView(col)
        setContentView(scroll)

        col.addView(text("KOOKY KEYBOARD", 22f, Ui.ORANGE, bold = true))
        col.addView(text("定型文キーボード v0.10", 12f, Ui.SUB))

        // --- はじめに ---
        col.addView(section("はじめに"))
        statusText = text("", 13f, Ui.TEXT)
        col.addView(statusText)
        col.addView(button("① キーボードを有効にする", Ui.KEY) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }, blockParams())
        col.addView(button("② キーボードを切り替える", Ui.KEY) {
            imm().showInputMethodPicker()
        }, blockParams())
        val test = EditText(this).apply {
            hint = "ここで試し打ち"
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.SUB)
            background = Ui.round(Ui.CARD, dp(10).toFloat())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            minLines = 2
            gravity = Gravity.TOP
        }
        col.addView(test, blockParams())

        // --- キーボードのサイズ ---
        col.addView(section("キーボードのサイズ"))
        col.addView(text("上の試し打ち欄でキーボードを開いたまま調整できるよ", 11f, Ui.SUB))
        col.addView(sizeSlider("高さ", "height_pct", 100, 70, 150), blockParams())
        col.addView(sizeSlider("片手モードの幅", "onehand_pct", 78, 55, 95), blockParams())

        // --- キーボードの色 ---
        col.addView(section("キーボードの色"))
        col.addView(themePicker(), blockParams())
        col.addView(sizeSlider("背景の透過率", "bg_trans", 0, 0, 90), blockParams())
        col.addView(text("透過率はキーボードの背景だけにかかるよ（キーは透けない）", 11f, Ui.SUB))

        // --- 定型文 ---
        col.addView(section("定型文"))
        col.addView(text("タブ長押しでカテゴリ名の変更・削除", 11f, Ui.SUB))
        val tabScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        tabsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabScroll.addView(tabsRow)
        col.addView(tabScroll, LinearLayout.LayoutParams(match, dp(40)).apply { topMargin = dp(8) })

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(listBox, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(8) })

        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addRow.addView(button("＋ 定型文", Ui.ORANGE) { editPhrase(-1) },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(4) })
        addRow.addView(button("＋ カテゴリ", Ui.KEY) { editCategory(-1) },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(4) })
        col.addView(addRow, blockParams())

        // --- バックアップ ---
        col.addView(section("バックアップ"))
        col.addView(text("機種変更や入れ直しのときに使ってね", 11f, Ui.SUB))
        col.addView(button("書き出し（クリップボードにコピー）", Ui.KEY) { exportData() }, blockParams())
        col.addView(button("読み込み（クリップボードから）", Ui.KEY) { importData() }, blockParams())

        // --- アップデート ---
        col.addView(section("アップデート"))
        col.addView(text("今のバージョン：v${currentVersion()}", 11f, Ui.SUB))
        col.addView(button("最新版をダウンロード", Ui.ORANGE) { openUpdatePage() }, blockParams())

        // --- 日本語変換 ---
        col.addView(section("日本語変換"))
        col.addView(text("選んだ変換は覚えて、次から先に出すよ", 11f, Ui.SUB))
        col.addView(button("変換の学習をリセット", Ui.KEY) {
            confirm("覚えた変換をすべて消す？") {
                Dict(this).clearLearning()
                toast("リセットしたよ")
            }
        }, blockParams())
        col.addView(button("辞書のライセンス", Ui.KEY) { showDictLicense() }, blockParams())

        render()
    }

    // 変換辞書（Mozc OSS辞書／IPAdic等）のライセンス表示
    private fun showDictLicense() {
        val body = try {
            assets.open("dict_license.txt").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "ライセンス文を読み込めなかったよ"
        }
        val tv = TextView(this).apply {
            text = "変換辞書は Mozc（Google）のオープンソース辞書を元にしています。\n\n$body"
            textSize = 11f
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setTextIsSelectable(true)
        }
        val sv = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle("辞書のライセンス")
            .setView(sv)
            .setPositiveButton("閉じる", null)
            .show()
    }

    // ---------- アップデート ----------

    private val releasesUrl = "https://github.com/shinichitakebuchi-netizen/kooky-keyboard/releases/latest"

    private fun currentVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private fun openUpdatePage() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(releasesUrl)))
        } catch (e: Exception) {
            toast("ブラウザを開けなかったよ")
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // キーボード選択ダイアログを閉じた後にも状態を更新
        if (hasFocus) updateStatus()
    }

    // ---------- 状態表示 ----------

    private fun imm(): InputMethodManager =
        getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private fun isEnabled(): Boolean =
        imm().enabledInputMethodList.any { it.packageName == packageName }

    private fun isSelected(): Boolean {
        val id = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: return false
        return id.startsWith("$packageName/")
    }

    private fun updateStatus() {
        statusText.text = when {
            !isEnabled() -> "⚠ まだ有効になってないよ。①から有効にしてね"
            !isSelected() -> "✅ 有効になってる。②で切り替えると使えるよ"
            else -> "✅ 今このキーボードが選ばれてるよ"
        }
    }

    // ---------- 一覧 ----------

    private fun saveAndRender() {
        store.save(cats)
        render()
    }

    private fun render() {
        if (current >= cats.size) current = maxOf(0, cats.size - 1)

        tabsRow.removeAllViews()
        cats.forEachIndexed { i, c ->
            val selected = i == current
            val chip = TextView(this).apply {
                text = c.name
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
                setTextColor(if (selected) Ui.BG else Ui.TEXT)
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                background = Ui.pressable(if (selected) Ui.ORANGE else Ui.CARD, dp(4).toFloat())
                setOnClickListener {
                    current = i
                    render()
                }
                setOnLongClickListener {
                    editCategory(i)
                    true
                }
            }
            tabsRow.addView(chip, LinearLayout.LayoutParams(wrap, match).apply { rightMargin = dp(6) })
        }

        listBox.removeAllViews()
        val cat = cats.getOrNull(current)
        if (cat == null) {
            listBox.addView(text("カテゴリがないよ。「＋ カテゴリ」から作ってね", 13f, Ui.SUB))
            return
        }
        if (cat.phrases.isEmpty()) {
            listBox.addView(text("このカテゴリは空だよ。「＋ 定型文」から追加してね", 13f, Ui.SUB))
            return
        }
        cat.phrases.forEachIndexed { i, p -> listBox.addView(phraseRow(cat, i, p), blockParams()) }
    }

    private fun phraseRow(cat: Category, index: Int, p: Phrase): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.pressable(Ui.CARD, dp(10).toFloat())
            setPadding(dp(12), dp(10), dp(6), dp(10))
            setOnClickListener { editPhrase(index) }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = p.label()
            setTextColor(Ui.TEXT)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        texts.addView(TextView(this).apply {
            text = p.body.replace("\n", " ")
            setTextColor(Ui.SUB)
            textSize = 12f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(texts, LinearLayout.LayoutParams(0, wrap, 1f))

        // 並べ替え
        row.addView(smallKey("▲") {
            if (index > 0) {
                val tmp = cat.phrases[index - 1]
                cat.phrases[index - 1] = cat.phrases[index]
                cat.phrases[index] = tmp
                saveAndRender()
            }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        row.addView(smallKey("▼") {
            if (index < cat.phrases.size - 1) {
                val tmp = cat.phrases[index + 1]
                cat.phrases[index + 1] = cat.phrases[index]
                cat.phrases[index] = tmp
                saveAndRender()
            }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        return row
    }

    // ---------- 編集ダイアログ ----------

    private fun editPhrase(index: Int) {
        val cat = cats.getOrNull(current)
        if (cat == null) {
            toast("先にカテゴリを作ってね")
            return
        }
        val p = if (index >= 0) cat.phrases[index] else null

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val titleEdit = EditText(this).apply {
            hint = "見出し（ボタンに表示・空でもOK）"
            setText(p?.title ?: "")
            setSingleLine()
        }
        val bodyEdit = EditText(this).apply {
            hint = "本文（入力される文章）"
            setText(p?.body ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP
        }
        box.addView(titleEdit)
        box.addView(bodyEdit)

        val builder = AlertDialog.Builder(this)
            .setTitle(if (p == null) "定型文を追加（${cat.name}）" else "定型文を編集")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val body = bodyEdit.text.toString()
                if (body.isBlank()) {
                    toast("本文が空だよ")
                    return@setPositiveButton
                }
                val title = titleEdit.text.toString().trim()
                if (p == null) {
                    cat.phrases.add(Phrase(title, body))
                } else {
                    p.title = title
                    p.body = body
                }
                saveAndRender()
            }
            .setNegativeButton("キャンセル", null)
        if (p != null) {
            builder.setNeutralButton("削除") { _, _ ->
                confirm("「${p.label()}」を削除する？") {
                    cat.phrases.removeAt(index)
                    saveAndRender()
                }
            }
        }
        builder.show()
    }

    private fun editCategory(index: Int) {
        val c = if (index >= 0) cats[index] else null
        val nameEdit = EditText(this).apply {
            hint = "カテゴリ名（例：接客）"
            setText(c?.name ?: "")
            setSingleLine()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(nameEdit)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(if (c == null) "カテゴリを追加" else "カテゴリを編集")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val name = nameEdit.text.toString().trim()
                if (name.isEmpty()) {
                    toast("名前が空だよ")
                    return@setPositiveButton
                }
                if (c == null) {
                    cats.add(Category(name, mutableListOf()))
                    current = cats.size - 1
                } else {
                    c.name = name
                }
                saveAndRender()
            }
            .setNegativeButton("キャンセル", null)
        if (c != null) {
            builder.setNeutralButton("削除") { _, _ ->
                confirm("「${c.name}」と中の定型文${c.phrases.size}件を削除する？") {
                    cats.removeAt(index)
                    saveAndRender()
                }
            }
        }
        builder.show()
    }

    private fun confirm(message: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> onYes() }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // ---------- バックアップ ----------

    private fun clipboard(): ClipboardManager =
        getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun exportData() {
        clipboard().setPrimaryClip(ClipData.newPlainText("kooky-keyboard", PhraseStore.toJson(cats)))
        toast("クリップボードにコピーしたよ。メモ等に貼って保存してね")
    }

    private fun importData() {
        val raw = clipboard().primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        if (raw.isNullOrBlank()) {
            toast("クリップボードが空だよ")
            return
        }
        val parsed = try {
            PhraseStore.fromJson(raw)
        } catch (e: Exception) {
            toast("読み込めない形式だよ")
            return
        }
        val count = parsed.sumOf { it.phrases.size }
        confirm("今のデータを上書きして、カテゴリ${parsed.size}件・定型文${count}件を読み込む？") {
            cats = parsed
            current = 0
            saveAndRender()
            toast("読み込んだよ")
        }
    }

    // ---------- 部品 ----------

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun section(s: String) = text(s, 16f, Ui.TEXT, bold = true).apply {
        setPadding(0, dp(24), 0, dp(4))
    }

    private fun button(label: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        minHeight = dp(46)
        setTextColor(if (color == Ui.ORANGE) Ui.BG else Ui.TEXT)
        typeface = Typeface.DEFAULT_BOLD
        background = Ui.pressable(color, dp(10).toFloat())
        setOnClickListener { onClick() }
    }

    private fun smallKey(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(Ui.SUB)
        setOnClickListener { onClick() }
    }

    private fun blockParams() = LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(8) }

    // 配色の選択（ネイビー / ブラック / ライト）。各ボタンはその配色の見本になっている
    private fun themePicker(): View {
        val prefs = getSharedPreferences("kooky_keyboard", Context.MODE_PRIVATE)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chips = ArrayList<Pair<TextView, Palette>>()
        fun paint() {
            val now = Themes.of(prefs.getString("theme", "navy"))
            for ((tv, p) in chips) {
                val on = p === now
                val d = Ui.round(p.bg, dp(10).toFloat())
                d.setStroke(dp(if (on) 3 else 1), if (on) p.accent else Ui.SUB)
                tv.background = d
                tv.text = if (on) "✓ ${p.label}" else p.label
                tv.setTextColor(p.text)
            }
        }
        Themes.ALL.forEachIndexed { i, p ->
            val tv = TextView(this).apply {
                textSize = 14f
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setOnClickListener {
                    prefs.edit().putString("theme", p.name).apply()
                    paint()
                }
            }
            chips.add(tv to p)
            row.addView(tv, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                if (i > 0) leftMargin = dp(6)
            })
        }
        paint()
        return row
    }

    // サイズ調整スライダー（5%刻み、キーボード側は次に開いたときに反映）
    private fun sizeSlider(label: String, key: String, def: Int, lo: Int, hi: Int): View {
        val prefs = getSharedPreferences("kooky_keyboard", Context.MODE_PRIVATE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.round(Ui.CARD, dp(10).toFloat())
            setPadding(dp(12), dp(10), dp(12), dp(6))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(text(label, 14f, Ui.TEXT), LinearLayout.LayoutParams(0, wrap, 1f))
        val valueText = text("", 14f, Ui.ORANGE, bold = true)
        head.addView(valueText)
        box.addView(head)

        val steps = (hi - lo) / 5
        val start = prefs.getInt(key, def).coerceIn(lo, hi)
        valueText.text = "$start%"
        val bar = SeekBar(this).apply {
            this.max = steps
            progress = (start - lo) / 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = lo + p * 5
                    valueText.text = "$v%"
                    prefs.edit().putInt(key, v).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        box.addView(bar, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(4) })

        box.addView(text("標準に戻す", 11f, Ui.SUB).apply {
            setPadding(0, dp(4), 0, dp(4))
            setOnClickListener { bar.progress = (def - lo) / 5 }
        })
        return box
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
