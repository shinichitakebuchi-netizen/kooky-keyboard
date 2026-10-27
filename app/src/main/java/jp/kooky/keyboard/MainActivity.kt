package jp.kooky.keyboard

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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

    // 今表示しているページの部品（ページを切り替えると作り直す）
    private var statusText: TextView? = null
    private var tabsRow: LinearLayout? = null
    private var listBox: LinearLayout? = null

    private lateinit var scrollView: ScrollView
    private lateinit var col: LinearLayout
    private var page = "top"

    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PhraseStore(this)
        cats = store.load()
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        scrollView = ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
        }
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(48))
        }
        scrollView.addView(col)
        setContentView(scrollView)
        showPage("top")
    }

    // ---------- ページの切り替え ----------

    // 各ページの名前（メニューの見出し・説明）
    private val pages = listOf(
        Triple("start", "はじめに", "キーボードの有効化・切り替え・試し打ち"),
        Triple("phrase", "定型文", "定型文とカテゴリの編集・バックアップ"),
        Triple("look", "見た目", "高さ・片手の幅・色・透過"),
        Triple("voice", "音声入力・AI添削", "マイクの許可・Gemini APIキー"),
        Triple("conv", "日本語変換", "単語登録・学習のリセット・辞書"),
        Triple("update", "アップデート", "最新版のダウンロード")
    )

    private fun showPage(p: String) {
        page = p
        statusText = null
        tabsRow = null
        listBox = null
        micStatus = null
        userBox = null
        col.removeAllViews()
        scrollView.scrollTo(0, 0)

        // 見た目ページだけ、キーボードを画面の上に重ねて出す（後ろの背景が透けて見えるように）
        if (p == "look") {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            setContentView(buildLookRoot())
            return
        }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (scrollView.parent == null) setContentView(scrollView)

        if (p == "top") {
            buildTop()
        } else {
            pageHeader(pages.firstOrNull { it.first == p }?.second ?: "")
            when (p) {
                "start" -> buildStart()
                "phrase" -> buildPhrase()
                "voice" -> buildVoice()
                "conv" -> buildConv()
                "update" -> buildUpdate()
            }
        }
        updateStatus()
        updateMicStatus()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (page != "top") showPage("top") else super.onBackPressed()
    }

    // サブページの見出し：‹ 戻る ＋ タイトル
    private fun pageHeader(title: String) {
        col.addView(text("‹ 設定に戻る", 14f, Ui.ORANGE, bold = true).apply {
            setPadding(0, dp(4), dp(16), dp(12))
            setOnClickListener { showPage("top") }
        })
        col.addView(text(title, 22f, Ui.TEXT, bold = true))
    }

    private fun buildTop() {
        col.addView(text("KOOKY KEYBOARD", 22f, Ui.ORANGE, bold = true))
        col.addView(text("定型文キーボード v${currentVersion()}", 12f, Ui.SUB))

        // 今の状態（有効・選択中か）
        val st = text("", 13f, Ui.TEXT).apply {
            background = Ui.round(Ui.CARD, dp(10).toFloat())
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        statusText = st
        col.addView(st, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(16) })

        col.addView(text("設定", 13f, Ui.SUB).apply { setPadding(dp(4), dp(20), 0, dp(2)) })
        for ((key, title, sub) in pages) col.addView(menuRow(title, sub) { showPage(key) }, blockParams())
    }

    private fun menuRow(title: String, sub: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.pressable(Ui.CARD, dp(12).toFloat())
            setPadding(dp(16), dp(14), dp(14), dp(14))
            setOnClickListener { onClick() }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 16f, Ui.TEXT, bold = true))
        texts.addView(text(sub, 12f, Ui.SUB).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(texts, LinearLayout.LayoutParams(0, wrap, 1f))
        row.addView(text("›", 22f, Ui.SUB))
        return row
    }

    private fun testField(): View = EditText(this).apply {
        hint = "ここで試し打ち"
        setTextColor(Ui.TEXT)
        setHintTextColor(Ui.SUB)
        background = Ui.round(Ui.CARD, dp(10).toFloat())
        setPadding(dp(12), dp(12), dp(12), dp(12))
        minLines = 2
        gravity = Gravity.TOP
    }

    // ---------- はじめに ----------
    private fun buildStart() {
        val st = text("", 13f, Ui.TEXT).apply { setPadding(0, dp(12), 0, 0) }
        statusText = st
        col.addView(st)
        col.addView(button("① キーボードを有効にする", Ui.KEY) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }, blockParams())
        col.addView(button("② キーボードを切り替える", Ui.KEY) {
            imm().showInputMethodPicker()
        }, blockParams())
        col.addView(testField(), blockParams())
    }

    // ---------- 定型文 ----------
    private fun buildPhrase() {
        col.addView(text("タブ長押しでカテゴリ名の変更・削除／定型文は右の二本線を長押しで並べ替え", 11f, Ui.SUB)
            .apply { setPadding(0, dp(8), 0, 0) })
        val tabScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val tr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabScroll.addView(tr)
        tabsRow = tr
        col.addView(tabScroll, LinearLayout.LayoutParams(match, dp(40)).apply { topMargin = dp(8) })

        val lb = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listBox = lb
        col.addView(lb, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(8) })

        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addRow.addView(button("＋ 定型文", Ui.ORANGE) { editPhrase(-1) },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(4) })
        addRow.addView(button("＋ カテゴリ", Ui.KEY) { editCategory(-1) },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(4) })
        col.addView(addRow, blockParams())
        col.addView(button("カテゴリの並べ替え", Ui.KEY) { sortCategories() }, blockParams())

        col.addView(section("バックアップ"))
        col.addView(text("機種変更や入れ直しのときに使ってね", 11f, Ui.SUB))
        col.addView(button("書き出し（クリップボードにコピー）", Ui.KEY) { exportData() }, blockParams())
        col.addView(button("読み込み（クリップボードから）", Ui.KEY) { importData() }, blockParams())
        render()
    }

    // ---------- 見た目 ----------
    // 上：試し打ち欄と調整項目（スクロール）／下：透過を確認するための背景（キーボードの後ろに来る）
    private fun buildLookRoot(): View {
        val frame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }

        val sv = ScrollView(this)
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        sv.addView(c)
        frame.addView(sv, LinearLayout.LayoutParams(match, 0, 1f))

        c.addView(text("‹ 設定に戻る", 14f, Ui.ORANGE, bold = true).apply {
            setPadding(0, dp(4), dp(16), dp(12))
            setOnClickListener { showPage("top") }
        })
        c.addView(text("見た目", 22f, Ui.TEXT, bold = true))
        c.addView(text("試し打ち欄をタップしてキーボードを出したまま調整すると、その場で反映されるよ", 11f, Ui.SUB)
            .apply { setPadding(0, dp(8), 0, 0) })
        c.addView(testField(), blockParams())

        c.addView(section("背景画像"))
        val bgStatus = text("", 13f, Ui.TEXT)
        fun paintBg() {
            bgStatus.text = when {
                BgImage.isAnimated(this) && Build.VERSION.SDK_INT >= 28 -> "✅ GIF（動く画像）を設定中（背景の透過率で見え方を調整）"
                BgImage.isAnimated(this) -> "✅ 画像を設定中（この端末ではGIFは止まった1コマ目で表示）"
                BgImage.has(this) -> "✅ 画像を設定中（背景の透過率で見え方を調整）"
                else -> "画像なし（写真・GIFが使えるよ）"
            }
        }
        paintBg()
        bgStatusRefresh = { paintBg() }
        c.addView(bgStatus)
        val bgRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bgRow.addView(button("画像を選ぶ", Ui.ORANGE) { pickBgImage() },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(4) })
        bgRow.addView(button("画像を外す", Ui.KEY) {
            BgImage.clear(this)
            paintBg()
            toast("背景画像を外したよ")
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(4) })
        c.addView(bgRow, blockParams())

        c.addView(section("透過"))
        c.addView(sizeSlider("背景の透過率", "bg_trans", 0, 0, 90), blockParams())
        c.addView(sizeSlider("キーの透過率", "key_trans", 0, 0, 90), blockParams())

        c.addView(section("色"))
        c.addView(themePicker(), blockParams())

        c.addView(section("サイズ"))
        c.addView(sizeSlider("高さ", "height_pct", 100, 70, 150), blockParams())
        c.addView(sizeSlider("片手モードの幅", "onehand_pct", 78, 55, 95), blockParams())

        // 確認用の背景：キーボードはこの上に重なる
        frame.addView(previewBackdrop(), LinearLayout.LayoutParams(match, dp(320)))
        return frame
    }

    // カラフルな縞模様＋文字。透過率を上げるとキーボードの後ろに見える
    private fun previewBackdrop(): View {
        val fl = android.widget.FrameLayout(this)
        val stripes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val colors = listOf("#F28C28", "#F2F0EB", "#2F6FD6", "#14213D", "#E04F5F", "#3BAA6E")
        for (hex in colors) {
            stripes.addView(View(this).apply { setBackgroundColor(android.graphics.Color.parseColor(hex)) },
                LinearLayout.LayoutParams(0, match, 1f))
        }
        fl.addView(stripes, android.widget.FrameLayout.LayoutParams(match, match))
        fl.addView(TextView(this).apply {
            text = "透過の確認用の背景\nKOOKY KEYBOARD"
            textSize = 22f
            setTextColor(android.graphics.Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 2f, android.graphics.Color.BLACK)
        }, android.widget.FrameLayout.LayoutParams(match, match))
        return fl
    }

    // ---------- 音声入力・AI添削 ----------
    private fun buildVoice() {
        col.addView(text("キーボードのマイクで話す →「添削して入力」でAIがビジネス敬語に整えて自動で入力するよ", 11f, Ui.SUB)
            .apply { setPadding(0, dp(8), 0, 0) })

        col.addView(section("マイク"))
        val ms = text("", 13f, Ui.TEXT)
        micStatus = ms
        col.addView(ms)
        col.addView(button("マイクの使用を許可", Ui.KEY) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)
        }, blockParams())

        col.addView(section("Gemini API"))
        val prefs = getSharedPreferences("kooky_keyboard", Context.MODE_PRIVATE)
        val keyEdit = EditText(this).apply {
            hint = "Gemini APIキー"
            setText(prefs.getString("gemini_key", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.SUB)
            background = Ui.round(Ui.CARD, dp(10).toFloat())
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        col.addView(keyEdit, blockParams())
        val modelEdit = EditText(this).apply {
            hint = "モデル名（空欄なら ${Gemini.DEFAULT_MODEL}）"
            setText(prefs.getString("gemini_model", Gemini.DEFAULT_MODEL))
            setSingleLine()
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.SUB)
            background = Ui.round(Ui.CARD, dp(10).toFloat())
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        col.addView(modelEdit, blockParams())
        val aiRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        aiRow.addView(button("保存", Ui.ORANGE) {
            prefs.edit()
                .putString("gemini_key", keyEdit.text.toString().trim())
                .putString("gemini_model", modelEdit.text.toString().trim().ifEmpty { Gemini.DEFAULT_MODEL })
                .apply()
            toast("保存したよ")
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(4) })
        aiRow.addView(button("接続テスト", Ui.KEY) {
            testGemini(keyEdit.text.toString().trim(), modelEdit.text.toString().trim())
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(4) })
        col.addView(aiRow, blockParams())
        col.addView(button("APIキーを取得するページを開く", Ui.KEY) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
            } catch (e: Exception) {
                toast("ブラウザを開けなかったよ")
            }
        }, blockParams())
        col.addView(text(
            "⚠ Gemini APIの無料枠では、送った文章がGoogleのサービス改善に使われることがあるよ。" +
                "お客様の名前・電話番号などの個人情報は話さないようにしてね。",
            11f, Ui.ORANGE
        ).apply { setPadding(0, dp(12), 0, 0) })
    }

    // ---------- 日本語変換 ----------
    private fun buildConv() {
        col.addView(section("単語登録"))
        col.addView(text("店名・商品名・人の名前など、よく使う言葉を登録すると変換で優先して出るよ", 11f, Ui.SUB))
        val ub = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ub, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(8) })
        userBox = ub
        col.addView(button("＋ 単語を登録", Ui.ORANGE) { editUserWord(null) }, blockParams())
        renderUserWords()

        col.addView(section("学習"))
        col.addView(text("選んだ変換は覚えて、次から先に出すよ", 11f, Ui.SUB))
        col.addView(button("変換の学習をリセット", Ui.KEY) {
            confirm("覚えた変換をすべて消す？") {
                Dict(this).clearLearning()
                toast("リセットしたよ")
            }
        }, blockParams())

        col.addView(section("辞書"))
        col.addView(button("辞書のライセンス", Ui.KEY) { showDictLicense() }, blockParams())
    }

    // ---------- アップデート ----------
    private fun buildUpdate() {
        col.addView(text("今のバージョン：v${currentVersion()}", 13f, Ui.SUB).apply { setPadding(0, dp(12), 0, 0) })
        col.addView(button("最新版をダウンロード", Ui.ORANGE) { openUpdatePage() }, blockParams())
    }

    // ---------- 単語登録 ----------

    private var userBox: LinearLayout? = null

    private fun renderUserWords() {
        val box = userBox ?: return
        box.removeAllViews()
        val list = Dict(this).userWords()
        if (list.isEmpty()) {
            box.addView(text("まだ登録がないよ", 13f, Ui.SUB))
            return
        }
        for ((r, w) in list) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.pressable(Ui.CARD, dp(10).toFloat())
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener { editUserWord(r to w) }
            }
            row.addView(text(w, 15f, Ui.TEXT, bold = true), LinearLayout.LayoutParams(0, wrap, 1f))
            row.addView(text(r, 13f, Ui.SUB))
            box.addView(row, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(6) })
        }
    }

    // 読みはひらがな（カタカナで入れても、ひらがなに直して保存）
    private fun toHiragana(s: String): String {
        val sb = StringBuilder()
        for (ch in s.trim()) sb.append(if (ch in '\u30A1'..'\u30F6') ch - 0x60 else ch)
        return sb.toString()
    }

    private fun editUserWord(old: Pair<String, String>?) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val wordEdit = EditText(this).apply {
            hint = "単語（例：前橋モール店）"
            setText(old?.second ?: "")
            setSingleLine()
        }
        val readEdit = EditText(this).apply {
            hint = "読み（ひらがな。例：まえばしもーるてん）"
            setText(old?.first ?: "")
            setSingleLine()
        }
        box.addView(wordEdit)
        box.addView(readEdit)
        val builder = AlertDialog.Builder(this)
            .setTitle(if (old == null) "単語を登録" else "登録を編集")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val w = wordEdit.text.toString().trim()
                val r = toHiragana(readEdit.text.toString())
                if (w.isEmpty() || r.isEmpty()) {
                    toast("単語と読みを両方入れてね")
                    return@setPositiveButton
                }
                if (!r.all { it in '\u3041'..'\u3096' || it == 'ー' }) {
                    toast("読みはひらがなで入れてね")
                    return@setPositiveButton
                }
                val d = Dict(this)
                if (old != null) d.removeUserWord(old.first, old.second)
                d.addUserWord(r, w)
                renderUserWords()
                toast("登録したよ")
            }
            .setNegativeButton("キャンセル", null)
        if (old != null) {
            builder.setNeutralButton("削除") { _, _ ->
                confirm("「${old.second}」の登録を消す？") {
                    Dict(this).removeUserWord(old.first, old.second)
                    renderUserWords()
                }
            }
        }
        builder.show()
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

    private var micStatus: TextView? = null

    private fun updateMicStatus() {
        val ok = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        micStatus?.text = if (ok) "✅ マイクは許可済み" else "⚠ マイクがまだ許可されてないよ"
    }

    // ---------- 背景画像 ----------

    private var bgStatusRefresh: (() -> Unit)? = null

    // ギャラリーから選ぶ（Android 13以降は写真の選択画面。許可は不要）
    private fun pickBgImage() {
        val intent = if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES)
        } else {
            Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
        }
        try {
            startActivityForResult(intent, 20)
        } catch (e: Exception) {
            try {
                startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }, 20)
            } catch (e2: Exception) {
                toast("画像を選ぶ画面を開けなかったよ")
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 20 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        toast("画像を読み込み中…")
        Thread {
            var err: String? = null
            val ok = try {
                BgImage.save(this, uri)
            } catch (e: BgImage.TooBig) {
                err = e.message
                false
            }
            runOnUiThread {
                if (err != null) {
                    toast(err!!)
                    return@runOnUiThread
                }
                if (ok) {
                    // 透過率が0だと画像が見えないので、最初は半分くらい見えるようにする
                    val prefs = getSharedPreferences("kooky_keyboard", Context.MODE_PRIVATE)
                    if (prefs.getInt("bg_trans", 0) < 30) prefs.edit().putInt("bg_trans", 50).apply()
                    toast("背景画像を設定したよ")
                    if (page == "look") showPage("look") else bgStatusRefresh?.invoke()
                } else {
                    toast("画像を読み込めなかったよ")
                }
            }
        }.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateMicStatus()
    }

    // 試しの文章を送って、添削結果を表示
    private fun testGemini(key: String, model: String) {
        if (key.isEmpty()) {
            toast("APIキーを入れてね")
            return
        }
        val sample = "えーと明日の会議なんですけどあの10時からでお願いしますそれと資料は前日までに送ります"
        toast("テスト中…")
        Thread {
            val msg = try {
                val r = Gemini.correct(key, model, sample)
                "✅ 成功（${r.model}）\n\n元の文：\n$sample\n\n添削後：\n${r.text}"
            } catch (e: Exception) {
                "⚠ 失敗\n\n${e.message}"
            }
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("接続テスト")
                    .setMessage(msg)
                    .setPositiveButton("閉じる", null)
                    .show()
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        updateMicStatus()
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
        val st = statusText ?: return
        st.text = when {
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
        // 定型文ページを開いていないときは何もしない
        val tabsRow = tabsRow ?: return
        val listBox = listBox ?: return
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
        val ds = DragSort { from, to ->
            val moved = cat.phrases.removeAt(from)
            cat.phrases.add(to, moved)
            saveAndRender()
        }
        cat.phrases.forEachIndexed { i, p ->
            val (row, handle) = phraseRow(i, p)
            listBox.addView(row, blockParams())
            ds.add(row, handle)
        }
    }

    // 定型文の1行：タップで編集、右の二本線を長押しで並べ替え
    private fun phraseRow(index: Int, p: Phrase): Pair<View, View> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.pressable(Ui.CARD, dp(10).toFloat())
            setPadding(dp(12), dp(10), dp(4), dp(10))
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
        val handle = dragHandle()
        row.addView(handle, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(4) })
        return row to handle
    }

    // 並べ替えのつまみ（二本線）
    private fun dragHandle(): View = android.widget.ImageView(this).apply {
        setImageResource(R.drawable.ic_kb_drag)
        setColorFilter(Ui.SUB)
        scaleType = android.widget.ImageView.ScaleType.CENTER
        contentDescription = "長押しで並べ替え"
    }

    // カテゴリの並べ替え（キーボードの定型文パネルもこの順に並ぶ）
    private fun sortCategories() {
        if (cats.size < 2) {
            toast("カテゴリが2つ以上になったら並べ替えできるよ")
            return
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        lateinit var repaint: () -> Unit
        repaint = {
            list.removeAllViews()
            val ds = DragSort { from, to ->
                val moved = cats.removeAt(from)
                cats.add(to, moved)
                current = 0
                repaint()
            }
            cats.forEach { c ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = Ui.round(Ui.CARD, dp(10).toFloat())
                    setPadding(dp(12), dp(4), dp(4), dp(4))
                }
                row.addView(text(c.name, 15f, Ui.TEXT, bold = true), LinearLayout.LayoutParams(0, wrap, 1f))
                val handle = dragHandle()
                row.addView(handle, LinearLayout.LayoutParams(dp(44), dp(44)))
                list.addView(row, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(6) })
                ds.add(row, handle)
            }
        }
        repaint()
        val sv = ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            addView(list)
        }
        AlertDialog.Builder(this)
            .setTitle("カテゴリの並べ替え（二本線を長押しで移動）")
            .setView(sv)
            .setPositiveButton("完了") { _, _ -> saveAndRender() }
            .setOnCancelListener { saveAndRender() }
            .show()
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

        // 入れるカテゴリ（タップで選び直し）
        var target = current
        val catBtn = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(12), 0, dp(4))
            setTextColor(Ui.ORANGE)
        }
        fun paintCat() { catBtn.text = "カテゴリ：${cats[target].name}　▼ 変更" }
        paintCat()
        catBtn.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("カテゴリを選ぶ")
                .setSingleChoiceItems(cats.map { it.name }.toTypedArray(), target) { d, which ->
                    target = which
                    paintCat()
                    d.dismiss()
                }
                .show()
        }
        box.addView(catBtn)

        val builder = AlertDialog.Builder(this)
            .setTitle(if (p == null) "定型文を追加" else "定型文を編集")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val body = bodyEdit.text.toString()
                if (body.isBlank()) {
                    toast("本文が空だよ")
                    return@setPositiveButton
                }
                val title = titleEdit.text.toString().trim()
                if (p == null) {
                    cats[target].phrases.add(Phrase(title, body))
                } else {
                    p.title = title
                    p.body = body
                    // カテゴリを変えたら、移動先の一番下へ
                    if (target != current) {
                        cat.phrases.removeAt(index)
                        cats[target].phrases.add(p)
                    }
                }
                current = target
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
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(Ui.TEXT)
        background = Ui.pressable(Ui.KEY, dp(8).toFloat())
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
