package jp.kooky.keyboard

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
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
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
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

    // ───────── 色：日本の伝統色（縁とそろえる）。名前は役割で、値は Iro にまとめてある ─────────
    // 庵と同じ色。キーボードの配色に合わせて、墨（ダーク）と白練（ライト）を切り替える
    //   ダーク：墨の地・鳥の子色の文字・甕覗70%の補足 ／ ライト：白練の地・藍色の文字・利休鼠の補足。罫線はどちらも利休鼠
    //   配色が「白練」のとき、または「移ろい」で日の出〜日の入りのときにライト。見出し・強調は差し色（初期値は紅緋）
    private var SUMI = Iro.SUMI               // 背景
    private var KINARI = Iro.TORINOKO         // 文字（主）
    private var USUZUMI = Iro.KAMENOZOKI_70   // 文字（補足）
    private val LINE_C = Iro.RIKYUNEZUMI      // 罫線・枠線
    private var lightUi = false
    private var SHU = Iro.BENIHI              // 見出し・強調：差し色（設定の「差し色」。初期値は紅緋）
    private val ACTIVE = Iro.WAKATAKE         // 稼働中：若竹色
    // 部品の目印（色としては使わない。CARD＝入力欄、KEYC＝枠だけのボタン）
    private val CARD = 0x01000001
    private val KEYC = 0x01000002
    // 文字のフォント（設定の「文字」で選ぶ。0=明朝（標準） 1=Kaisei Tokumin 2=Yuji Syuku）
    private var fontIdx = 0
    private val MINCHO: Typeface get() = AppFonts.typeface(this, fontIdx)
    private val MINCHO_BOLD: Typeface get() = AppFonts.bold(this, fontIdx)

    // 表題の右の「● 状態」
    private var topState: Pair<TextView, View>? = null

    // 面：角はほぼ四角（2dp）。朱→生成りの塗り、ボタン→細い枠、入力欄→ごく薄い面＋細い枠
    private fun box(color: Int, r: Float): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(2).toFloat()
        when (color) {
            SHU -> setColor(KINARI)
            KEYC, CARD -> { setColor(Color.TRANSPARENT); setStroke(dp(1), LINE_C) }
            else -> setColor(color)
        }
    }

    private fun press(color: Int, r: Float): Drawable =
        RippleDrawable(ColorStateList.valueOf((0x22 shl 24) or (KINARI and 0x00FFFFFF)), box(color, r), ColorDrawable(Color.WHITE))

    // 下だけの細い罫線
    private fun hairlineBottom(thin: Boolean): Drawable {
        val l = LayerDrawable(arrayOf(ColorDrawable(LINE_C)))
        l.setLayerGravity(0, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
        l.setLayerHeight(0, if (thin) maxOf(1, (resources.displayMetrics.density * 0.5f).toInt()) else dp(1))
        return l
    }

    // スライダー：2dpの線（未選択＝利休鼠、選択＝鳥の子色）と、背景色の丸（直径16dp・鳥の子色の1.5dp枠）だけ
    private fun SeekBar.sumi() {
        val bgLine = GradientDrawable().apply { setColor(LINE_C) }
        val fgLine = ClipDrawable(GradientDrawable().apply { setColor(KINARI) }, Gravity.START, ClipDrawable.HORIZONTAL)
        val track = LayerDrawable(arrayOf(bgLine, fgLine))
        track.setId(0, android.R.id.background)
        track.setId(1, android.R.id.progress)
        for (i in 0..1) {
            track.setLayerGravity(i, Gravity.CENTER_VERTICAL or Gravity.FILL_HORIZONTAL)
            track.setLayerHeight(i, dp(2))
        }
        progressDrawable = track
        thumb = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(SUMI)
            setStroke((resources.displayMetrics.density * 1.5f).toInt().coerceAtLeast(1), KINARI)
            setSize(dp(16), dp(16))
        }
        splitTrack = false
        background = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fontIdx = AppFonts.current(this)
        store = PhraseStore(this)
        cats = store.load()
        applyUiColors()

        scrollView = ScrollView(this).apply {
            setBackgroundColor(SUMI)
            isFillViewport = true
        }
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(36), dp(28), dp(32))
        }
        scrollView.addView(col)
        setContentView(scrollView)
        showPage("top")
        showBetaNotice()
    }

    // 見出し・強調の色は、キーボードと同じ差し色（選んでいなければ紅緋）
    private fun accentNow(): Int {
        val a = getSharedPreferences("kooky_keyboard", Context.MODE_PRIVATE).getInt("accent", 0)
        return if (a == 0) Iro.BENIHI else a or (0xFF shl 24)
    }

    // ライトにするか：配色が白練、または移ろいの昼
    private fun wantLight(): Boolean =
        Themes.of(getSharedPreferences("kooky_keyboard", Context.MODE_PRIVATE).getString("theme", "sumi")).name == "kinari"

    private fun applyUiColors() {
        SHU = accentNow()
        lightUi = wantLight()
        SUMI = if (lightUi) Iro.SHIRONERI else Iro.SUMI
        KINARI = if (lightUi) Iro.AI else Iro.TORINOKO
        USUZUMI = if (lightUi) Iro.RIKYUNEZUMI else Iro.KAMENOZOKI_70
        window.statusBarColor = SUMI
        window.navigationBarColor = SUMI
        // ライトのときはステータスバー・ナビゲーションバーのアイコンを暗い色に
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (lightUi)
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        if (::scrollView.isInitialized) scrollView.setBackgroundColor(SUMI)
    }

    // 差し色や墨⇔白練が変わっていたら、色を入れ替えて今のページを作り直す（スクロール位置はそのまま）
    private fun refreshUiColors() {
        if (accentNow() == SHU && wantLight() == lightUi) return
        applyUiColors()
        val y = currentScroll()?.scrollY ?: 0
        showPage(page)
        currentScroll()?.post { currentScroll()?.scrollTo(0, y) }
    }

    private var lookScroll: ScrollView? = null
    private fun currentScroll(): ScrollView? = if (page in LOOK_PAGES) lookScroll else scrollView

    // 大きさのページで、今の傾きを表示する（角度を決める目安に）
    private var rollSensor: HandSensor? = null
    private var rollText: TextView? = null
    private fun startRollReadout(tv: TextView) {
        stopRollReadout()
        rollText = tv
        val hs = HandSensor(this) { r ->
            val a = kotlin.math.abs(r).toInt()
            tv.text = when {
                a < 1 -> "今の傾き：ほぼ水平"
                r > 0 -> "今の傾き：右に ${a}°"
                else -> "今の傾き：左に ${a}°"
            }
        }
        if (!hs.available) { tv.text = "この端末では傾きを読めません"; return }
        rollSensor = hs
        hs.start()
    }
    private fun stopRollReadout() {
        rollSensor?.stop()
        rollSensor = null
    }

    // ベータ版を初めて開いたときのお知らせ
    private fun showBetaNotice() {
        if (!BuildConfig.IS_BETA) return
        val prefs = getSharedPreferences("kooky_keyboard", Context.MODE_PRIVATE)
        if (prefs.getBoolean("beta_notice", false)) return
        AlertDialog.Builder(this)
            .setTitle("綴 ベータ版")
            .setMessage(
                "試してくれてありがとう！ これは個人が作っているテスト中のキーボードです。\n\n" +
                    "・打った文字や定型文は、この端末の中だけで使います。外部には送りません\n" +
                    "・例外として、マイクの長押し（AI添削）を使ったときだけ、話した文章がGoogleのGemini APIに送られます（自分のAPIキーが必要）\n" +
                    "・パスワードなど大事な入力は、念のため普段のキーボードに切り替えて入力してください\n" +
                    "・不具合や使いにくい所があったら、作った人に教えてください\n\n" +
                    "新しい版は「アップデート」から入れられます。"
            )
            .setPositiveButton("OK") { _, _ -> prefs.edit().putBoolean("beta_notice", true).apply() }
            .setCancelable(false)
            .show()
    }

    // ---------- ページの切り替え ----------

    // 各ページの名前（メニューの見出し・説明）
    // 設定の階層（庵と同じく、一覧 → 各ページ）
    private val pages = listOf(
        "start" to "はじめに",
        "phrase" to "定型文",
        "tone" to "色合い",
        "font" to "文字",
        "bg" to "背景・透過",
        "size" to "大きさ",
        "fan" to "扇形",
        "voice" to "音声入力・AI添削",
        "conv" to "日本語変換",
        "update" to "アップデート"
    )
    // キーボードを出したまま調整するページ（下に透過確認用の背景が付く）
    private val LOOK_PAGES = setOf("tone", "font", "bg", "size", "fan")

    // 一覧の右下に出す、今の設定の要約
    private fun summary(key: String): String {
        val pr = getSharedPreferences("kooky_keyboard", Context.MODE_PRIVATE)
        return when (key) {
            "start" -> when {
                !isEnabled() -> "まだ有効になっていません"
                !isSelected() -> "有効・切り替えると使えます"
                else -> "使用中"
            }
            "phrase" -> "${cats.size}カテゴリ・${cats.sumOf { it.phrases.size }}件"
            "tone" -> {
                val t = pr.getString("theme", "sumi")
                val name = if (t == Themes.UTSUROI) "移ろい" else Themes.of(t).label
                val a = pr.getInt("accent", 0)
                val an = if (a == 0) "紅緋" else Iro.PRESETS.firstOrNull { (it.second and 0xFFFFFF) == (a and 0xFFFFFF) }?.first ?: "#" + String.format("%06X", a and 0xFFFFFF)
                "$name・差し色 $an"
            }
            "font" -> AppFonts.names[fontIdx]
            "bg" -> listOf(if (BgImage.has(this)) "画像あり" else "画像なし",
                "背景 ${pr.getInt("bg_trans", 0)}%", "キー ${pr.getInt("key_trans", 0)}%").joinToString("・")
            "size" -> "高さ ${pr.getInt("height_pct", 100)}%・片手 ${pr.getInt("onehand_pct", 78)}%"
            "fan" -> pr.getInt("tilt_deg", 0).let { if (it == 0) "なし" else "${it}°・" + if (pr.getString("tilt_dir", "right") == "left") "左手用" else "右手用" }
            "voice" -> if (pr.getString("gemini_key", "").isNullOrEmpty()) "APIキー未設定" else "APIキー設定済み"
            "conv" -> listOf(if (pr.getBoolean("live_conv", true)) "ライブ変換" else "ライブ変換なし",
                "登録 ${Dict(this).userWords().size}語").joinToString("・")
            else -> ""
        }
    }

    private fun showPage(p: String) {
        page = p
        stopRollReadout()
        statusText = null
        topState = null
        tabsRow = null
        listBox = null
        micStatus = null
        userBox = null
        col.removeAllViews()
        scrollView.scrollTo(0, 0)

        // 見た目のページは、キーボードを画面の上に重ねて出す（後ろの背景が透けて見えるように）
        if (p in LOOK_PAGES) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            val root = buildLookRoot(p)
            AppFonts.apply(root, this, fontIdx)
            setContentView(root)
            return
        }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (scrollView.parent == null) setContentView(scrollView)

        if (p == "top") {
            buildTop()
        } else {
            pageHeader(col, pages.firstOrNull { it.first == p }?.second ?: "")
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
        AppFonts.apply(col, this, fontIdx)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (page != "top") showPage("top") else super.onBackPressed()
    }

    // ページの上（庵と同じ）：‹　設定（一覧へ戻る）・閉じる　＋ 差し色の見出し
    private fun pageHeader(c: LinearLayout, title: String) {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nav.addView(text("‹　設定", 14f, USUZUMI).apply {
            letterSpacing = 0.15f
            setPadding(0, dp(8), dp(16), dp(8))
            setOnClickListener { showPage("top") }
        })
        nav.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        nav.addView(closeText().apply { setPadding(dp(16), dp(8), 0, dp(8)) })
        c.addView(nav)
        c.addView(section(title))
    }

    private fun closeText() = text("閉じる", 13f, USUZUMI).apply {
        letterSpacing = 0.15f
        setOnClickListener { finish() }
    }

    // 小見出し（庵の label と同じ）：鳥の子色 15sp
    private fun label(t: String) = text(t, 15f, KINARI).apply {
        layoutParams = LinearLayout.LayoutParams(wrap, wrap).apply {
            topMargin = dp(18)
            bottomMargin = dp(2)
        }
    }

    private fun buildTop() {
        // 表題：綴　つづり　　　　　閉じる（庵と同じ）
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        head.addView(text("綴", 44f, KINARI).apply { typeface = MINCHO_BOLD })
        head.addView(text("つづり", 13f, USUZUMI).apply {
            letterSpacing = 0.23f
            setPadding(dp(12), 0, 0, dp(10))
        })
        head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        head.addView(closeText().apply { setPadding(dp(12), dp(8), 0, dp(10)) })
        col.addView(head)
        col.addView(note("キーボードの見た目と入力を整えます。変えた内容はすぐにキーボードに反映されます。"),
            LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(10) })

        col.addView(section("設定"))
        for ((key, title) in pages.filter { it.first != "update" }) col.addView(menuRow(title, summary(key)) { showPage(key) })

        // 更新（縁・庵と同じく、いちばん下に小さく）
        col.addView(text("更新する　v${currentVersion()}" + if (BuildConfig.IS_BETA) "（ベータ版）" else "", 13f, USUZUMI).apply {
            letterSpacing = 0.15f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { openUpdatePage() }
        }, LinearLayout.LayoutParams(wrap, wrap).apply {
            topMargin = dp(56)
            gravity = Gravity.CENTER_HORIZONTAL
        })
    }

    // メニューの1行：罫線で区切るだけ（箱は使わない）
    private fun menuRow(title: String, sub: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = hairlineBottom(true)
            setPadding(0, dp(16), dp(2), dp(16))
            setOnClickListener { onClick() }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 15f, KINARI))
        texts.addView(text(sub, 12f, USUZUMI).apply { setPadding(0, dp(4), 0, 0) })
        row.addView(texts, LinearLayout.LayoutParams(0, wrap, 1f))
        row.addView(text("›", 20f, USUZUMI))
        return row
    }

    private fun testField(): View = EditText(this).apply {
        hint = "ここで試し打ち"
        setTextColor(KINARI)
        setHintTextColor(USUZUMI)
        background = box(CARD, dp(10).toFloat())
        setPadding(dp(12), dp(12), dp(12), dp(12))
        minLines = 2
        gravity = Gravity.TOP
    }

    // ---------- はじめに ----------
    private fun buildStart() {
        val st = text("", 13f, KINARI).apply { setPadding(0, dp(12), 0, 0) }
        statusText = st
        col.addView(st)
        col.addView(button("① キーボードを有効にする", KEYC) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }, blockParams())
        col.addView(button("② キーボードを切り替える", KEYC) {
            imm().showInputMethodPicker()
        }, blockParams())
        col.addView(testField(), blockParams())
    }

    // ---------- 定型文 ----------
    private fun buildPhrase() {
        col.addView(text("タブ長押しでカテゴリ名の変更・削除／定型文は右の二本線を長押しで並べ替え", 11f, USUZUMI)
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
        addRow.addView(button("＋ 定型文", SHU) { editPhrase(-1) },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(4) })
        addRow.addView(button("＋ カテゴリ", KEYC) { editCategory(-1) },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(4) })
        col.addView(addRow, blockParams())
        col.addView(button("カテゴリの並べ替え", KEYC) { sortCategories() }, blockParams())

        col.addView(label("バックアップ"))
        col.addView(text("機種変更や入れ直しのときに使ってね", 11f, USUZUMI))
        col.addView(button("書き出し（クリップボードにコピー）", KEYC) { exportData() }, blockParams())
        col.addView(button("読み込み（クリップボードから）", KEYC) { importData() }, blockParams())
        render()
    }

    // ---------- 見た目（色合い・文字・背景・透過・大きさ・扇形） ----------
    // 上：試し打ち欄と調整項目（スクロール）／下：透過を確認するための背景（キーボードの後ろに来る）
    private fun buildLookRoot(sub: String): View {
        val frame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SUMI)
        }

        val sv = ScrollView(this)
        lookScroll = sv
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(36), dp(28), dp(32))
        }
        sv.addView(c)
        frame.addView(sv, LinearLayout.LayoutParams(match, 0, 1f))

        pageHeader(c, pages.firstOrNull { it.first == sub }?.second ?: "")
        c.addView(note("試し打ち欄をタップしてキーボードを出したまま調整すると、その場で反映されます。")
            .apply { setPadding(0, dp(4), 0, 0) })
        c.addView(testField(), blockParams())

        when (sub) {
            "tone" -> {
                c.addView(label("配色"))
                c.addView(themePicker(), blockParams())
                c.addView(label("差し色"))
                c.addView(accentPicker(), blockParams())
                c.addView(note("確定キー・選んでいる変換候補・オンのボタン・設定の見出しなどの色です。")
                    .apply { setPadding(0, dp(10), 0, 0) })
            }
            "font" -> {
                c.addView(fontPicker(), blockParams())
                c.addView(note("Kaisei Tokumin・Yuji Syuku は SIL Open Font License 1.1 の無料フォントです。設定画面の文字が変わります（キーボードの文字は端末の標準のまま）。")
                    .apply { setPadding(0, dp(10), 0, 0) })
            }
            "bg" -> {
                c.addView(label("背景画像"))
                val bgStatus = text("", 13f, USUZUMI)
                fun paintBg() {
                    bgStatus.text = when {
                        BgImage.isAnimated(this) && Build.VERSION.SDK_INT >= 28 -> "GIF（動く画像）を設定中。背景の透過率で見え方を調整します。"
                        BgImage.isAnimated(this) -> "画像を設定中（この端末ではGIFは止まった1コマ目で表示）。"
                        BgImage.has(this) -> "画像を設定中。背景の透過率で見え方を調整します。"
                        else -> "画像なし（写真・GIFが使えます）。"
                    }
                }
                paintBg()
                bgStatusRefresh = { paintBg() }
                c.addView(bgStatus)
                val bgRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                bgRow.addView(button("画像を選ぶ", SHU) { pickBgImage() },
                    LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(4) })
                bgRow.addView(button("画像を外す", KEYC) {
                    BgImage.clear(this)
                    paintBg()
                    toast("背景画像を外したよ")
                }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(4) })
                c.addView(bgRow, blockParams())
                c.addView(button("トリミング（表示する範囲を選ぶ）", KEYC) {
                    if (BgImage.has(this)) openCrop() else toast("先に画像を選んでね")
                }, blockParams())

                c.addView(label("透過"))
                c.addView(sizeSlider("背景の透過率", "bg_trans", 0, 0, 90), blockParams())
                c.addView(sizeSlider("キーの透過率", "key_trans", 0, 0, 90), blockParams())

                c.addView(label("ナビゲーションバー"))
                c.addView(navPicker(), blockParams())
                c.addView(note("画面下の ⌨ ∨ のバーです。「透明」はキーボードの背景（色・画像）をバーの裏まで伸ばします。端末によっては効かないことがあるので、そのときは「キーボードと同じ色」に戻してください。")
                    .apply { setPadding(0, dp(10), 0, 0) })
            }
            "size" -> {
                c.addView(sizeSlider("高さ", "height_pct", 100, 70, 150), blockParams())
                c.addView(sizeSlider("片手モードの幅", "onehand_pct", 78, 55, 95), blockParams())
                c.addView(sizeSlider("フローティングの幅", "float_pct", 88, 50, 100), blockParams())
                c.addView(note("フローティングはキーボードのツールバーの「浮いた窓」アイコンでオン・オフ。上の二本線をドラッグで移動できます。")
                    .apply { setPadding(0, dp(10), 0, 0) })

                // 傾きで片手モードに自動で寄せる
                c.addView(label("傾きで片手モード"))
                c.addView(choice2("auto_hand_s", "off", listOf("on" to "入", "off" to "切"), "傾きで自動で寄せる"), blockParams())
                c.addView(text("寄せる向き", 14f, KINARI).apply { setPadding(0, dp(14), 0, dp(8)) })
                c.addView(choice2("auto_hand_dir", "same", listOf("same" to "傾いた側に寄せる", "reverse" to "反対側に寄せる")))
                c.addView(sizeSlider("傾きの角度", "auto_hand_deg", 8, 3, 20, "°"), blockParams())
                val rollText = text("", 13f, SHU).apply { setPadding(0, dp(10), 0, 0) }
                c.addView(rollText)
                startRollReadout(rollText)
                c.addView(note("スマホを左右どちらかに、この角度より傾けたまま約1秒たつと、キーボードがその側に寄ります。水平に戻すと全幅に戻ります。打っている途中は動きません。キーボードの片手ボタンで切り替えたときは、キーボードを閉じるまで自動はお休みします。寝転んでいるときなどは、傾きがうまく読めないことがあります。")
                    .apply { setPadding(0, dp(8), 0, 0) })
            }
            "fan" -> {
                c.addView(degSlider(), blockParams())
                c.addView(label("向き"))
                c.addView(tiltDirPicker(), blockParams())
                c.addView(note("右手用は左下がり、左手用は右下がりです。片手モードで左に寄せたときは、扇形もフリックも自動で左右反転します。")
                    .apply { setPadding(0, dp(10), 0, 0) })
                c.addView(sizeSlider("フリックの回転（扇形の傾きに対する割合）", "flick_pct", 100, 0, 200), blockParams())
                c.addView(label("フリックの向きの見本"))
                c.addView(FlickPreview(this).apply {
                    clipToOutline = true
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: android.graphics.Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, dp(2).toFloat())
                        }
                    }
                }, blockParams())
                c.addView(note("紅緋が上フリック。100％で列の向きどおり、0％でまっすぐの上下左右、200％で列の向きの2倍回します。")
                    .apply { setPadding(0, dp(10), 0, 0) })
                c.addView(note("キーを扇形の段違いに並べて、親指で押しやすくします。キーボードのツールバーの扇形アイコンで、角度をつける／元に戻すを切り替えられます。段違いにした分だけキーが少し小さくなるので、気になるときは高さを上げてください。")
                    .apply { setPadding(0, dp(8), 0, 0) })
            }
        }

        // 確認用の背景：キーボードはこの上に重なる
        frame.addView(previewBackdrop(), LinearLayout.LayoutParams(match, dp(320)))
        return frame
    }

    // 伝統色の縞模様＋文字。透過率を上げるとキーボードの後ろに見える
    private fun previewBackdrop(): View {
        val fl = android.widget.FrameLayout(this)
        val stripes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // 紅緋・鳥の子色・新橋色・藍色・韓紅花・萌黄
        val colors = listOf("#F35336", "#E2BE9F", "#006C7F", "#264348", "#C91F37", "#5B8930")
        for (hex in colors) {
            stripes.addView(View(this).apply { setBackgroundColor(android.graphics.Color.parseColor(hex)) },
                LinearLayout.LayoutParams(0, match, 1f))
        }
        fl.addView(stripes, android.widget.FrameLayout.LayoutParams(match, match))
        fl.addView(TextView(this).apply {
            text = "透過の確認用の背景\n綴"
            textSize = 22f
            setTextColor(KINARI)
            typeface = MINCHO_BOLD
            gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 2f, SUMI)
        }, android.widget.FrameLayout.LayoutParams(match, match))
        return fl
    }

    // ---------- 音声入力・AI添削 ----------
    private fun buildVoice() {
        col.addView(text("キーボードのマイクをタップでふつうの音声入力、長押しでAI添削モード（話した内容をビジネス敬語に整えて入力）になるよ", 11f, USUZUMI)
            .apply { setPadding(0, dp(8), 0, 0) })

        col.addView(label("マイク"))
        val ms = text("", 13f, KINARI)
        micStatus = ms
        col.addView(ms)
        col.addView(button("マイクの使用を許可", KEYC) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)
        }, blockParams())

        col.addView(label("Gemini API"))
        val prefs = getSharedPreferences("kooky_keyboard", Context.MODE_PRIVATE)
        val keyEdit = EditText(this).apply {
            hint = "Gemini APIキー"
            setText(prefs.getString("gemini_key", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            setTextColor(KINARI)
            setHintTextColor(USUZUMI)
            background = box(CARD, dp(10).toFloat())
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        col.addView(keyEdit, blockParams())
        val modelEdit = EditText(this).apply {
            hint = "モデル名（空欄なら ${Gemini.DEFAULT_MODEL}）"
            setText(prefs.getString("gemini_model", Gemini.DEFAULT_MODEL))
            setSingleLine()
            setTextColor(KINARI)
            setHintTextColor(USUZUMI)
            background = box(CARD, dp(10).toFloat())
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        col.addView(modelEdit, blockParams())
        val aiRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        aiRow.addView(button("保存", SHU) {
            prefs.edit()
                .putString("gemini_key", keyEdit.text.toString().trim())
                .putString("gemini_model", modelEdit.text.toString().trim().ifEmpty { Gemini.DEFAULT_MODEL })
                .apply()
            toast("保存したよ")
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(4) })
        aiRow.addView(button("接続テスト", KEYC) {
            testGemini(keyEdit.text.toString().trim(), modelEdit.text.toString().trim())
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(4) })
        col.addView(aiRow, blockParams())
        col.addView(button("APIキーを取得するページを開く", KEYC) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
            } catch (e: Exception) {
                toast("ブラウザを開けなかったよ")
            }
        }, blockParams())
        col.addView(text(
            "⚠ Gemini APIの無料枠では、送った文章がGoogleのサービス改善に使われることがあるよ。" +
                "お客様の名前・電話番号などの個人情報は話さないようにしてね。",
            11f, SHU
        ).apply { setPadding(0, dp(12), 0, 0) })
    }

    // ---------- 日本語変換 ----------
    private fun buildConv() {
        col.addView(label("単語登録"))
        col.addView(text("店名・商品名・人の名前など、よく使う言葉を登録すると変換で優先して出るよ", 11f, USUZUMI))
        val ub = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ub, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(8) })
        userBox = ub
        col.addView(button("＋ 単語を登録", SHU) { editUserWord(null) }, blockParams())
        renderUserWords()

        col.addView(label("入力のしかた"))
        val pr = getSharedPreferences("kooky_keyboard", Context.MODE_PRIVATE)
        pr.registerOnSharedPreferenceChangeListener(liveSync)
        col.addView(choice2("flick_s", if (pr.getBoolean("flick_on", true)) "on" else "off",
            listOf("on" to "入", "off" to "切"), "フリック入力"), blockParams())
        col.addView(text("オフにすると、どの向きに払ってもタップと同じになるよ", 11f, USUZUMI).apply { setPadding(0, dp(4), 0, 0) })
        col.addView(text("同じキーを続けてタップ", 15f, KINARI).apply { setPadding(0, dp(14), 0, 0) })
        col.addView(choice2("toggle_s", if (pr.getBoolean("toggle_on", true)) "on" else "off",
            listOf("on" to "あ→い→う", "off" to "あああ")), blockParams())
        col.addView(text("「あ→い→う」は、同じキーを続けてタップすると文字が切り替わる（ケータイ打ち）。「あああ」は、タップするたびに同じ文字が増える。両方オフにすると あ段しか打てないので、片方は必ずオンになるよ", 11f, USUZUMI)
            .apply { setPadding(0, dp(4), 0, 0) })

        col.addView(liveToggle(), blockParams())
        col.addView(text("オンにすると、打っている途中から漢字に変換して表示するよ。確定キーでそのまま確定、変換キーで別の候補を選べる", 11f, USUZUMI)
            .apply { setPadding(0, dp(6), 0, 0) })

        col.addView(label("学習"))
        col.addView(text("選んだ変換は覚えて、次から先に出すよ", 11f, USUZUMI))
        col.addView(button("変換の学習をリセット", KEYC) {
            confirm("覚えた変換をすべて消す？") {
                Dict(this).clearLearning()
                toast("リセットしたよ")
            }
        }, blockParams())

        col.addView(label("辞書"))
        col.addView(button("辞書のライセンス", KEYC) { showDictLicense() }, blockParams())
    }

    // ---------- アップデート ----------
    private fun buildUpdate() {
        col.addView(text("今のバージョン：v${currentVersion()}", 13f, USUZUMI).apply { setPadding(0, dp(12), 0, 0) })
        col.addView(button("最新版をダウンロード", SHU) { openUpdatePage() }, blockParams())
    }

    // ---------- 単語登録 ----------

    private var userBox: LinearLayout? = null

    private fun renderUserWords() {
        val box = userBox ?: return
        box.removeAllViews()
        val list = Dict(this).userWords()
        if (list.isEmpty()) {
            box.addView(text("まだ登録がないよ", 13f, USUZUMI))
            return
        }
        for ((r, w) in list) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = press(CARD, dp(10).toFloat())
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener { editUserWord(r to w) }
            }
            row.addView(text(w, 15f, KINARI, bold = true), LinearLayout.LayoutParams(0, wrap, 1f))
            row.addView(text(r, 13f, USUZUMI))
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

    private val releasesUrl = BuildConfig.UPDATE_URL

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

    // トリミング画面（キーボードと同じ形の枠に合わせて、画像を動かす・拡大縮小する）
    private fun openCrop() {
        val bmp = BgImage.load(this) ?: run {
            toast("画像を読み込めなかったよ")
            return
        }
        val prefs = getSharedPreferences("kooky_keyboard", Context.MODE_PRIVATE)
        val dm = resources.displayMetrics
        val hPct = prefs.getInt("height_pct", 100).coerceIn(70, 150)
        // キーボードの大きさ（ツールバー38＋キー208を高さ設定で伸縮、余白8）
        val kbH = ((38 + 208) * hPct / 100f + 8f) * dm.density
        val ratio = dm.widthPixels / kbH

        val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SUMI)
            setPadding(dp(16), dp(20), dp(16), dp(20))
        }
        root.addView(text("トリミング", 20f, KINARI, bold = true))
        root.addView(text("オレンジの枠がキーボードの範囲。画像を指で動かす・2本指で拡大縮小して決めてね", 12f, USUZUMI)
            .apply { setPadding(0, dp(6), 0, dp(8)) })
        val cv = CropView(this, bmp, ratio, BgImage.crop(this))
        root.addView(cv, LinearLayout.LayoutParams(match, 0, 1f))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("中央に戻す", KEYC) { cv.reset() },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(4) })
        row.addView(button("キャンセル", KEYC) { dlg.dismiss() },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
        row.addView(button("決定", SHU) {
            BgImage.setCrop(this, cv.result())
            toast("表示する範囲を決めたよ")
            dlg.dismiss()
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(4) })
        root.addView(row, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(12) })

        dlg.setContentView(root)
        dlg.setOnDismissListener { bmp.recycle() }
        dlg.show()
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
                    toast("表示する範囲を決めてね")
                    if (page in LOOK_PAGES) showPage(page) else bgStatusRefresh?.invoke()
                    openCrop()
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

    override fun onPause() {
        stopRollReadout()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (page == "size") rollText?.let { startRollReadout(it) }
        refreshUiColors()
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
        topState?.let { (t, dot) ->
            val ok = isEnabled() && isSelected()
            t.text = when {
                !isEnabled() -> "未設定"
                !isSelected() -> "待機中"
                else -> "使用中"
            }
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (ok) ACTIVE else SHU)
            }
        }
        val st = statusText ?: return
        st.text = when {
            !isEnabled() -> "まだ有効になっていません。「はじめに」の①から有効にしてください。"
            !isSelected() -> "有効になっています。「はじめに」の②で切り替えると使えます。"
            else -> "今このキーボードが選ばれています。"
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
                setTextColor(if (selected) SUMI else KINARI)
                                background = press(if (selected) SHU else CARD, dp(4).toFloat())
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
            listBox.addView(text("カテゴリがないよ。「＋ カテゴリ」から作ってね", 13f, USUZUMI))
            return
        }
        if (cat.phrases.isEmpty()) {
            listBox.addView(text("このカテゴリは空だよ。「＋ 定型文」から追加してね", 13f, USUZUMI))
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
            background = press(CARD, dp(10).toFloat())
            setPadding(dp(12), dp(10), dp(4), dp(10))
            setOnClickListener { editPhrase(index) }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = p.label()
            setTextColor(KINARI)
            textSize = 15f
            typeface = Typeface.DEFAULT
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        texts.addView(TextView(this).apply {
            text = p.body.replace("\n", " ")
            setTextColor(USUZUMI)
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
        setColorFilter(USUZUMI)
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
                    background = box(CARD, dp(10).toFloat())
                    setPadding(dp(12), dp(4), dp(4), dp(4))
                }
                row.addView(text(c.name, 15f, KINARI, bold = true), LinearLayout.LayoutParams(0, wrap, 1f))
                val handle = dragHandle()
                row.addView(handle, LinearLayout.LayoutParams(dp(44), dp(44)))
                list.addView(row, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(6) })
                ds.add(row, handle)
            }
        }
        repaint()
        val sv = ScrollView(this).apply {
            setBackgroundColor(SUMI)
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
            setTextColor(SHU)
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
        // 太字は使わず、少しだけ重みを出す（ミニマルに）
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(0f, 1.25f)
    }

    // 補足の文：薄墨・小さめ・行間ゆったり
    private fun note(s: String) = text(s, 12f, USUZUMI)

    // 見出し：明朝・朱・字間あり＋下に細い罫線
    private fun section(s: String) = text(s, 15f, SHU).apply {
        typeface = MINCHO
        letterSpacing = 0.27f
        background = hairlineBottom(false)
        setPadding(0, dp(40), 0, dp(10))
        layoutParams = LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dp(8) }
    }

    // ボタン：主（SHU を渡したもの）は生成りの塗り、ほかは細い枠だけ
    private fun button(label: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        minHeight = dp(46)
        letterSpacing = 0.05f
        setTextColor(if (color == SHU) SUMI else KINARI)
        background = press(color, 0f)
        setOnClickListener { onClick() }
    }

    private fun smallKey(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(KINARI)
        background = press(KEYC, dp(8).toFloat())
        setOnClickListener { onClick() }
    }

    private fun blockParams() = LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(8) }

    // 2択ボタン（オレンジが選択中）
    private fun choice2(key: String, def: String, opts: List<Pair<String, String>>, title: String = ""): View {
        val prefs = getSharedPreferences("kooky_keyboard", Context.MODE_PRIVATE)
        // 入／切（庵と同じ）：左に名前、右に「入　/　切」。行全体を押して切り替え、下に細い罫線
        if (opts.map { it.first } == listOf("on", "off")) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(14), 0, dp(14))
                background = hairlineBottom(true)
            }
            row.addView(text(title, 15f, KINARI), LinearLayout.LayoutParams(0, wrap, 1f))
            val on = text("入", 14f, SHU)
            val off = text("切", 14f, KINARI)
            row.addView(on)
            row.addView(text("　/　", 12f, LINE_C))
            row.addView(off)
            fun paint() {
                val v = prefs.getString(key, def) == "on"
                on.setTextColor(if (v) SHU else LINE_C)
                off.setTextColor(if (v) LINE_C else KINARI)
            }
            row.setOnClickListener {
                val v = prefs.getString(key, def) == "on"
                prefs.edit().putString(key, if (v) "off" else "on").apply()
                paint()
            }
            paint()
            return row
        }
        // 選択肢（庵と同じ）：選択中は鳥の子色で塗り（文字は墨）、それ以外は利休鼠の細い枠。角丸2dp
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btns = ArrayList<Pair<TextView, String>>()
        fun paint() {
            val now = prefs.getString(key, def)
            for ((tv, k) in btns) {
                val on = k == now
                tv.setTextColor(if (on) SUMI else KINARI)
                tv.background = GradientDrawable().apply {
                    cornerRadius = dp(2).toFloat()
                    if (on) setColor(KINARI) else { setColor(Color.TRANSPARENT); setStroke(dp(1), LINE_C) }
                }
            }
        }
        opts.forEachIndexed { i, (k, label) ->
            val tv = TextView(this).apply {
                text = label
                textSize = 14f
                setPadding(dp(16), dp(8), dp(16), dp(8))
                setOnClickListener {
                    prefs.edit().putString(key, k).apply()
                    paint()
                }
            }
            btns.add(tv to k)
            row.addView(tv, LinearLayout.LayoutParams(wrap, wrap).apply { if (i > 0) leftMargin = dp(10) })
        }
        paint()
        return row
    }

    private fun tiltDirPicker() = choice2("tilt_dir", "right", listOf("right" to "右手用", "left" to "左手用"))

    private fun liveToggle(): View {
        val prefs = getSharedPreferences("kooky_keyboard", Context.MODE_PRIVATE)
        // 保存は文字で行い、キーボード側は真偽値で読む
        val v = choice2("live_conv_s", if (prefs.getBoolean("live_conv", true)) "on" else "off",
            listOf("on" to "入", "off" to "切"), "ライブ変換")
        prefs.registerOnSharedPreferenceChangeListener(liveSync)
        return v
    }

    private val liveSync = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, k ->
        when (k) {
            "live_conv_s" -> p.edit().putBoolean("live_conv", p.getString(k, "on") == "on").apply()
            "flick_s", "toggle_s" -> {
                val flick = p.getString("flick_s", if (p.getBoolean("flick_on", true)) "on" else "off") == "on"
                val toggle = p.getString("toggle_s", if (p.getBoolean("toggle_on", true)) "on" else "off") == "on"
                // 両方オフだと あ段しか打てないので、今変えなかった方をオンに戻す
                if (!flick && !toggle) {
                    val other = if (k == "flick_s") "toggle_s" else "flick_s"
                    p.edit().putString(other, "on").apply()
                    toast("両方オフだと あ段しか打てないので、もう片方をオンにしたよ")
                    showPage(page)
                    return@OnSharedPreferenceChangeListener
                }
                p.edit().putBoolean("flick_on", flick).putBoolean("toggle_on", toggle).apply()
            }
        }
    }

    // キーの角度（0〜12度、1度刻み）
    private fun degSlider(): View {
        val prefs = getSharedPreferences("kooky_keyboard", Context.MODE_PRIVATE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = hairlineBottom(true)
            setPadding(0, dp(10), 0, dp(8))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(text("角度", 14f, KINARI), LinearLayout.LayoutParams(0, wrap, 1f))
        val vt = text("", 14f, SHU, bold = true)
        head.addView(vt)
        box.addView(head)
        val start = prefs.getInt("tilt_deg", 0).coerceIn(0, 12)
        vt.text = if (start == 0) "なし" else "${start}°"
        val bar = SeekBar(this).apply {
            sumi()
            max = 12
            progress = start
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    vt.text = if (p == 0) "なし" else "$p°"
                    prefs.edit().putInt("tilt_deg", p).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        box.addView(bar, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(4) })
        box.addView(text("なしに戻す", 11f, USUZUMI).apply {
            setPadding(0, dp(4), 0, dp(4))
            setOnClickListener { bar.progress = 0 }
        })
        return box
    }

    // ナビゲーションバーの見え方（キーボードと同じ色 / 透明）
    private fun navPicker(): View =
        choice2("nav_mode", "color", listOf("color" to "キーボードと同じ色", "clear" to "透明"))

    // 文字の選択：3つを縦に並べ、各行に「綴　つづり」をそのフォントで表示、右に小さくフォント名
    // 選択中は鳥の子色の細い枠、ほかは利休鼠の細い枠。行全体をタップで切り替え、すぐ画面に反映
    private fun fontPicker(): View {
        val prefs = getSharedPreferences("kooky_keyboard", Context.MODE_PRIVATE)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rows = ArrayList<View>()
        fun paint() {
            rows.forEachIndexed { i, r ->
                r.background = GradientDrawable().apply {
                    cornerRadius = dp(2).toFloat()
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(1), if (i == fontIdx) KINARI else LINE_C)
                }
            }
        }
        AppFonts.names.forEachIndexed { i, name ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setOnClickListener {
                    fontIdx = i
                    prefs.edit().putInt(AppFonts.PREF_KEY, i).apply()
                    paint()
                    AppFonts.apply(window.decorView, this@MainActivity, i)
                }
            }
            row.addView(TextView(this).apply {
                text = "綴　つづり"
                textSize = 22f
                setTextColor(KINARI)
                typeface = AppFonts.typeface(this@MainActivity, i)
                tag = AppFonts.KEEP
            }, LinearLayout.LayoutParams(0, wrap, 1f))
            row.addView(text(name, 12f, USUZUMI))
            rows.add(row)
            box.addView(row, LinearLayout.LayoutParams(match, wrap).apply { if (i > 0) topMargin = dp(10) })
        }
        paint()
        return box
    }

    // 差し色の選択（縁と同じ）：18色を6色ずつ3行。丸い色見本の下に小さく色名、選択中は鳥の子色の細い輪
    // 下に「#」＋6桁の色コードを入れて「適用」もできる。「配色の標準」で選んだ配色のもとの色に戻す
    private fun accentPicker(): View {
        val prefs = getSharedPreferences("kooky_keyboard", Context.MODE_PRIVATE)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val cells = ArrayList<Triple<View, TextView, Int>>()
        lateinit var hexEdit: EditText
        fun cur() = prefs.getInt("accent", 0)
        fun paint() {
            val now = cur()
            for ((sw, name, c) in cells) {
                val on = (if (now == 0) Iro.BENIHI else now) and 0xFFFFFF == c and 0xFFFFFF
                val ring = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    if (on) setStroke(dp(1), KINARI)
                }
                val dot = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    setStroke(maxOf(1, (resources.displayMetrics.density * 0.5f).toInt()), LINE_C)
                }
                val ld = LayerDrawable(arrayOf(ring, dot))
                ld.setLayerInset(1, dp(3), dp(3), dp(3), dp(3))
                sw.background = ld
                name.setTextColor(if (on) KINARI else USUZUMI)
            }
            if (now != 0 && !hexEdit.hasFocus()) hexEdit.setText(String.format("%06X", now and 0xFFFFFF))
        }
        fun pick(c: Int) {
            prefs.edit().putInt("accent", if (c == 0) 0 else c or (0xFF shl 24)).apply()
            paint()
            // 設定の見出しも差し色なので、ページを塗り直す
            refreshUiColors()
        }
        Iro.PRESETS.chunked(6).forEachIndexed { r, row ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { (name, c) ->
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, dp(8), 0, 0)
                    setOnClickListener { pick(c) }
                }
                val sw = View(this)
                cell.addView(sw, LinearLayout.LayoutParams(dp(32), dp(32)))
                val tv = text(name, 10f, USUZUMI).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setPadding(0, dp(3), 0, 0)
                }
                cell.addView(tv)
                cells.add(Triple(sw, tv, c))
                line.addView(cell, LinearLayout.LayoutParams(0, wrap, 1f))
            }
            box.addView(line, LinearLayout.LayoutParams(match, wrap))
        }
        // #色コード ＋ 適用
        val hexRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hexRow.addView(text("#", 14f, USUZUMI))
        hexEdit = EditText(this).apply {
            textSize = 14f
            setTextColor(KINARI)
            setHintTextColor(LINE_C)
            hint = "F35336"
            letterSpacing = 0.14f
            isSingleLine = true
            filters = arrayOf(android.text.InputFilter.AllCaps(), android.text.InputFilter.LengthFilter(6))
            background = hairlineBottom(true)
            setPadding(dp(6), dp(8), dp(6), dp(8))
        }
        hexRow.addView(hexEdit, LinearLayout.LayoutParams(0, wrap, 1f).apply { leftMargin = dp(6) })
        hexRow.addView(button("適用", KEYC) {
            val h = hexEdit.text.toString().trim()
            if (Regex("[0-9A-Fa-f]{6}").matches(h)) pick(0xFF000000.toInt() or h.toInt(16))
            else Toast.makeText(this, "6桁の色コード（例：F35336）を入れてね", Toast.LENGTH_SHORT).show()
        }.apply { minWidth = dp(72) }, LinearLayout.LayoutParams(wrap, dp(40)).apply { leftMargin = dp(14) })
        box.addView(hexRow, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(14) })
        paint()
        return box
    }

    // 配色（庵と同じ）：墨 / 白練 / 移ろい を文字の選択肢で。ほかの配色はその下に
    private fun themePicker(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(choice2("theme", "sumi", listOf("sumi" to "墨", "kinari" to "白練", Themes.UTSUROI to "移ろい")))
        val (rise, set) = ThemeClock.sunTimes()
        box.addView(note("移ろいは、日の出から日の入りまで白練、それ以外は墨になります。" +
            "今日は ${ThemeClock.fmt(rise)} 〜 ${ThemeClock.fmt(set)}（前橋）。").apply { setPadding(0, dp(10), 0, 0) })
        box.addView(text("ほかの配色", 12f, USUZUMI).apply { setPadding(0, dp(14), 0, dp(8)) })
        box.addView(choice2("theme", "sumi", Themes.ALL.filter { !it.wa }.map { it.name to it.label }))
        // 片方を選んだら、もう片方の表示も直す
        val pr = getSharedPreferences("kooky_keyboard", Context.MODE_PRIVATE)
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
            if (k == "theme") {
                // 墨⇔白練が変われば設定画面の色も切り替える
                if (wantLight() != lightUi) { refreshUiColors(); return@OnSharedPreferenceChangeListener }
                val y = lookScroll?.scrollY ?: 0; showPage(page); lookScroll?.post { lookScroll?.scrollTo(0, y) }
            }
        }
        themeListener?.let { pr.unregisterOnSharedPreferenceChangeListener(it) }
        themeListener = l
        pr.registerOnSharedPreferenceChangeListener(l)
        return box
    }
    private var themeListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    // サイズ調整スライダー（1%刻み）。細かい調整は − ＋ ボタンで1%ずつ
    private fun sizeSlider(label: String, key: String, def: Int, lo: Int, hi: Int, unit: String = "%"): View {
        val prefs = getSharedPreferences("kooky_keyboard", Context.MODE_PRIVATE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = hairlineBottom(true)
            setPadding(0, dp(10), 0, dp(8))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(text(label, 14f, KINARI), LinearLayout.LayoutParams(0, wrap, 1f))
        val valueText = text("", 14f, SHU, bold = true).apply {
            gravity = Gravity.CENTER
            minWidth = dp(52)
        }

        val start = prefs.getInt(key, def).coerceIn(lo, hi)
        valueText.text = "$start$unit"
        val bar = SeekBar(this).apply {
            sumi()
            this.max = hi - lo
            progress = start - lo
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = lo + p
                    valueText.text = "$v$unit"
                    prefs.edit().putInt(key, v).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        fun stepBtn(label: String, delta: Int) = text(label, 18f, KINARI, bold = true).apply {
            gravity = Gravity.CENTER
            background = press(KEYC, dp(8).toFloat())
            setOnClickListener { bar.progress = (bar.progress + delta).coerceIn(0, bar.max) }
        }
        head.addView(stepBtn("−", -1), LinearLayout.LayoutParams(dp(38), dp(34)))
        head.addView(valueText)
        head.addView(stepBtn("＋", 1), LinearLayout.LayoutParams(dp(38), dp(34)))
        box.addView(head)
        box.addView(bar, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(4) })

        box.addView(text("標準に戻す", 11f, USUZUMI).apply {
            setPadding(0, dp(4), 0, dp(4))
            setOnClickListener { bar.progress = def - lo }
        })
        return box
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
