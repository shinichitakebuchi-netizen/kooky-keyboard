package jp.kooky.keyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlin.math.abs

class KookyKeyboardService : InputMethodService() {

    companion object {
        const val TAB_JA = 0
        const val TAB_EN = 1
        const val TAB_NUM = 2
        const val TAB_PHRASE = 3 // ここから定型文カテゴリ
    }

    private lateinit var store: PhraseStore
    private lateinit var dict: Dict
    private var categories: MutableList<Category> = mutableListOf()

    private var tab = TAB_JA
    private var userTab = TAB_JA

    // 入力切替キーで あ→A→1 を順に切り替える（定型文から戻るときはこのモードへ）
    private var inputMode = TAB_JA
    private var modeKey: TextView? = null

    // サイズ設定（％）。設定画面で変わったら作り直す
    private var heightPct = 100
    private var oneHandPct = 78

    // 配色と背景の透過率（％）
    private var pal: Palette = Themes.NAVY
    private var bgTrans = 0

    // 背景色に透過率をかけたもの（キー自体は透過しない）
    private fun bgColor(): Int {
        val alpha = (255 * (100 - bgTrans) / 100).coerceIn(0, 255)
        return (alpha shl 24) or (pal.bg and 0x00FFFFFF)
    }

    // 今の見た目の設定（変わっていたらキーボードを作り直す）
    private fun lookKey(): String {
        val pr = prefs()
        return listOf(
            pr.getInt("height_pct", 100).coerceIn(70, 150),
            pr.getInt("onehand_pct", 78).coerceIn(55, 95),
            pr.getString("theme", "navy"),
            pr.getInt("bg_trans", 0).coerceIn(0, 90),
            pr.getInt("key_trans", 0).coerceIn(0, 90)
        ).joinToString("|")
    }

    private var builtLook = ""

    // ---------- 音声入力 ----------
    private var voicePanel: View? = null
    private var voiceStatus: TextView? = null
    private var voiceBody: TextView? = null
    private var voiceMainBtn: TextView? = null
    private var voiceSubBtn: TextView? = null
    private var recognizer: SpeechRecognizer? = null
    private val voiceBuf = StringBuilder()
    private var voiceListening = false
    private var voiceStopping = false
    private var voiceFinished = false
    private var lastRaw = ""
    private var lastInserted = ""
    private var voiceStage = 0 // 0=非表示 1=聞き取り中 2=添削中 3=入力済み 4=お知らせ
    private var voiceToken = 0 // キャンセル後に古い結果が入らないようにする番号

    // 押したときの波紋（ライトは暗め、それ以外は明るめ）
    private fun press(color: Int, radius: Float) =
        Ui.pressable(keyColor(color), radius, if (pal === Themes.LIGHT) 0x22000000 else 0x33FFFFFF)

    // 手作りアイコンを背景の真ん中に重ねる（押したときの波紋もそのまま）
    private fun iconBg(color: Int, radius: Float, icon: Int, tint: Int, sizeDp: Int = 22): Drawable {
        val bg = press(color, radius)
        val d = getDrawable(icon)?.mutate() ?: return bg
        d.setTint(tint)
        val ld = LayerDrawable(arrayOf(bg, d))
        ld.setLayerGravity(1, Gravity.CENTER)
        ld.setLayerSize(1, dp(sizeDp), dp(sizeDp))
        return ld
    }

    // キーの透過率をかけた色（文字は透けない）
    private var keyTrans = 0
    private fun keyColor(color: Int): Int {
        if (keyTrans == 0 || color == Color.TRANSPARENT) return color
        val a = (Color.alpha(color) * (100 - keyTrans) / 100).coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    // 片手モード 0=通常 1=右寄せ 2=左寄せ
    private var oneHand = 0

    // ---------- 日本語入力の状態 ----------
    private val composing = StringBuilder()
    private var cands: List<Cand> = emptyList()
    private var sel = -1

    // 同じキー連続タップで文字を切り替える（日本語・英語）
    private var toggleKey: Array<String>? = null
    private var toggleIdx = 0
    private var toggleTime = 0L
    private var toggleUpper = false

    // ---------- 英語の大文字 0=小文字 1=次の1文字だけ大文字 2=ずっと大文字 ----------
    private var shift = 0
    private val enLabelKeys = ArrayList<TextView>()
    private var caseKey: TextView? = null

    // ---------- ビュー ----------
    private var leftStrip: View? = null
    private var rightStrip: View? = null
    private val topIcons = ArrayList<View>()
    private var tabScroll: View? = null
    private var tabsRow: LinearLayout? = null
    private var candScroll: HorizontalScrollView? = null
    private var candRow: LinearLayout? = null
    private var listBox: LinearLayout? = null
    private var listScroll: ScrollView? = null
    private var jaGrid: View? = null
    private var enGrid: View? = null
    private var numGrid: View? = null
    private var spaceKey: TextView? = null
    private var phraseBtn: TextView? = null
    private var keysRow: View? = null
    private val enterKeys = ArrayList<TextView>()

    // 「元に戻す」用：直前にこのキーボードで確定した文字（読みがあれば変換前に戻せる）
    private var lastCommit = ""
    private var lastReading: String? = null

    // ---------- 長押しリピート（⌫・カーソル移動） ----------
    // 押しているキーが「押下中」かつ「画面に出ている」間だけ繰り返す
    private val handler = Handler(Looper.getMainLooper())
    private var repeatKey: View? = null
    private var repeatAction: (() -> Unit)? = null
    private val repeater = object : Runnable {
        override fun run() {
            val k = repeatKey
            val a = repeatAction
            if (k == null || a == null || !k.isPressed || !k.isAttachedToWindow || !k.isShown) {
                stopRepeat()
                return
            }
            a()
            handler.postDelayed(this, 60)
        }
    }

    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun prefs() = getSharedPreferences("kooky_keyboard", Context.MODE_PRIVATE)

    override fun onCreate() {
        super.onCreate()
        store = PhraseStore(this)
        dict = Dict(this)
        dict.prepare()
        prefs().registerOnSharedPreferenceChangeListener(lookListener)
    }

    // 見た目の設定（高さ・色・透過など）が変わったら、表示中のキーボードをすぐ作り直す
    private val lookKeys = setOf("height_pct", "onehand_pct", "theme", "bg_trans", "key_trans")
    private val rebuildLook = Runnable {
        if (isInputViewShown && lookKey() != builtLook) {
            stopRepeat()
            cancelVoice()
            commitAll()
            setInputView(onCreateInputView())
        }
    }
    private val lookListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key in lookKeys) {
            handler.removeCallbacks(rebuildLook)
            handler.postDelayed(rebuildLook, 120)
        }
    }

    override fun onCreateInputView(): View {
        stopRepeat()
        categories = store.load()
        oneHand = prefs().getInt("one_hand", 0)
        heightPct = prefs().getInt("height_pct", 100).coerceIn(70, 150)
        oneHandPct = prefs().getInt("onehand_pct", 78).coerceIn(55, 95)
        pal = Themes.of(prefs().getString("theme", "navy"))
        bgTrans = prefs().getInt("bg_trans", 0).coerceIn(0, 90)
        keyTrans = prefs().getInt("key_trans", 0).coerceIn(0, 90)
        builtLook = lookKey()
        // 透過したときに後ろが見えるよう、キーボードの窓自体の背景を消す
        window?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        enLabelKeys.clear()
        enterKeys.clear()
        topIcons.clear()

        // 外枠：[左の操作パネル][キーボード本体][右の操作パネル]（片手モードの空いた側に出る）
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(bgColor())
        }
        val stripWidth = (resources.displayMetrics.widthPixels * (100 - oneHandPct) / 100f).toInt()

        val ls = stripPanel(true)
        root.addView(ls, LinearLayout.LayoutParams(stripWidth, match))
        leftStrip = ls

        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        root.addView(p, LinearLayout.LayoutParams(0, wrap, 1f))

        val rs = stripPanel(false)
        root.addView(rs, LinearLayout.LayoutParams(stripWidth, match))
        rightStrip = rs

        // 上段（ツールバー）：[⚙][定型文タブ or 変換候補][片手][🎤]
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // ⚙ タップ：設定画面 / 長押し：キーボードの切り替え
        val gear = toolKey(R.drawable.ic_kb_gear) { openSettings() }
        gear.setOnLongClickListener {
            commitAll()
            imm().showInputMethodPicker()
            true
        }
        top.addView(gear, LinearLayout.LayoutParams(dp(40), match).apply { rightMargin = dp(4) })
        topIcons.add(gear)

        // 空き（変換中はここに変換候補が出る）
        val ts = View(this)
        top.addView(ts, LinearLayout.LayoutParams(0, match, 1f))
        tabScroll = ts

        val cs = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
        }
        val cr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cs.addView(cr)
        top.addView(cs, LinearLayout.LayoutParams(0, match, 1f))
        candScroll = cs
        candRow = cr

        // 定型文パネルの開閉
        val phr = toolKey(R.drawable.ic_kb_phrase) { togglePhrases() }
        top.addView(phr, LinearLayout.LayoutParams(dp(40), match).apply { leftMargin = dp(4) })
        topIcons.add(phr)
        phraseBtn = phr

        // 片手モードの切り替え（通常 ⇔ 右寄せ）
        val hand = toolKey(R.drawable.ic_kb_hand) { setOneHand(if (oneHand == 0) 1 else 0) }
        top.addView(hand, LinearLayout.LayoutParams(dp(44), match).apply { leftMargin = dp(4) })
        topIcons.add(hand)

        // 🎤 音声入力 → AIでビジネス敬語に添削 → 自動で入力
        val mic = toolKey(R.drawable.ic_kb_mic) { startVoice() }
        top.addView(mic, LinearLayout.LayoutParams(dp(40), match).apply { leftMargin = dp(4) })
        topIcons.add(mic)

        p.addView(top, LinearLayout.LayoutParams(match, sz(38)))

        // 本体（12キー配置）：[左列 ↶ ◀ @ あA1][中央：あ/A/1][右列 ⌫ ▶ ␣ ↵]
        val bodyFrame = FrameLayout(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        body.addView(leftColumn(), LinearLayout.LayoutParams(0, match, 1f))

        val area = FrameLayout(this)

        val jg = buildJaGrid()
        area.addView(jg, FrameLayout.LayoutParams(match, match))
        jaGrid = jg

        val eg = buildEnGrid()
        area.addView(eg, FrameLayout.LayoutParams(match, match))
        enGrid = eg

        val ng = buildNumGrid()
        area.addView(ng, FrameLayout.LayoutParams(match, match))
        numGrid = ng

        // 音声入力中に上にかぶせるパネル
        area.addView(buildVoicePanel(), FrameLayout.LayoutParams(match, match))

        body.addView(area, LinearLayout.LayoutParams(0, match, 3f))
        body.addView(rightColumn(), LinearLayout.LayoutParams(0, match, 1f))
        bodyFrame.addView(body, FrameLayout.LayoutParams(match, match))
        keysRow = body

        // 定型文パネル（開いている間はキーを隠す）
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            visibility = View.GONE
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), 0, dp(2), dp(6))
        }
        scroll.addView(box)
        bodyFrame.addView(scroll, FrameLayout.LayoutParams(match, match))
        listBox = box
        listScroll = scroll

        p.addView(bodyFrame, LinearLayout.LayoutParams(match, sz(208)).apply { topMargin = dp(2) })

        applyOneHand()
        render()
        refreshCandBar()
        updateCaseLabels()
        return root
    }

    // 高さ設定を反映したサイズ
    private fun sz(v: Int): Int = dp(v * heightPct / 100)

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        stopRepeat()
        clearComposingState()
        dict.prepare()
        if (dict.ready) dict.reloadUserIfChanged()
        // サイズ・色・透過率が変わっていたらキーボードを作り直す
        if (lookKey() != builtLook) setInputView(onCreateInputView())
        // 設定画面で編集した内容を毎回読み直す
        categories = store.load()
        if (!restarting) tab = startTab(info)
        if (tab > TAB_PHRASE) tab = inputMode
        shift = 0
        applyOneHand()
        render()
        refreshCandBar()
        updateCaseLabels()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        stopRepeat()
        cancelVoice()
        commitAll()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        stopRepeat()
        clearComposingState()
        super.onFinishInput()
    }

    override fun onWindowHidden() {
        stopRepeat()
        super.onWindowHidden()
    }

    override fun onDestroy() {
        prefs().unregisterOnSharedPreferenceChangeListener(lookListener)
        handler.removeCallbacks(rebuildLook)
        stopRepeat()
        cancelVoice()
        super.onDestroy()
    }

    // ユーザーが入力欄の別の場所をタップしたら変換中の状態を捨てる
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (composing.isNotEmpty() && candidatesEnd != -1 &&
            (newSelStart != candidatesEnd || newSelEnd != candidatesEnd)
        ) {
            currentInputConnection?.finishComposingText()
            clearComposingState()
            refreshCandBar()
        }
    }

    // 数字・電話番号欄は「1」、メール・URL・パスワード欄は「A」で開く
    private fun startTab(info: EditorInfo?): Int {
        val t = forcedTab(info) ?: return userTab
        inputMode = t
        return t
    }

    private fun forcedTab(info: EditorInfo?): Int? {
        val type = info?.inputType ?: return null
        val cls = type and InputType.TYPE_MASK_CLASS
        val v = type and InputType.TYPE_MASK_VARIATION
        if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) return TAB_NUM
        if (cls == InputType.TYPE_CLASS_TEXT && (
                v == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    v == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                    v == InputType.TYPE_TEXT_VARIATION_URI ||
                    v == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                )
        ) return TAB_EN
        return userTab
    }

    // ---------- 片手モード ----------

    private fun setOneHand(mode: Int) {
        oneHand = mode
        prefs().edit().putInt("one_hand", oneHand).apply()
        applyOneHand()
    }

    // 0=通常 1=右寄せ（左に操作パネル） 2=左寄せ（右に操作パネル）
    private fun applyOneHand() {
        leftStrip?.visibility = if (oneHand == 1) View.VISIBLE else View.GONE
        rightStrip?.visibility = if (oneHand == 2) View.VISIBLE else View.GONE
    }

    // 片手モードの空いた側：⤢ 全幅に戻す ／ ‹ › 反対側へ寄せる
    private fun stripPanel(isLeft: Boolean): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        col.addView(roundBtn(R.drawable.ic_kb_expand, true) { setOneHand(0) },
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { bottomMargin = dp(20) })
        col.addView(roundBtn(if (isLeft) R.drawable.ic_kb_left else R.drawable.ic_kb_right, false) {
            setOneHand(if (isLeft) 2 else 1)
        },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        return col
    }

    // 丸ボタン（strong=true はオレンジ：透過時でも見つけやすく）
    private fun roundBtn(icon: Int, strong: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
        background = if (strong) {
            iconBg(pal.accent, dp(24).toFloat(), icon, pal.onAccent, 24)
        } else {
            iconBg(pal.card, dp(24).toFloat(), icon, pal.text, 24)
        }
        setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        }
    }

    // ツールバーのアイコン
    private fun toolKey(icon: Int, onClick: () -> Unit): TextView = TextView(this).apply {
        background = iconBg(pal.card, dp(8).toFloat(), icon, pal.sub)
        setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        }
    }

    // 左列：↶ 元に戻す ／ ◀ ／ @ ／ あA1（入力切替）
    private fun leftColumn(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        col.addView(key("", pal.key).apply {
            background = iconBg(pal.key, dp(8).toFloat(), R.drawable.ic_kb_undo, pal.text)
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                undo()
            }
        }, colParams())

        col.addView(key("", pal.key).apply {
            background = iconBg(pal.key, dp(8).toFloat(), R.drawable.ic_kb_left, pal.text)
            repeatable(this) { cursor(KeyEvent.KEYCODE_DPAD_LEFT) }
        }, colParams())

        col.addView(key("@", pal.key).apply {
            textSize = 19f
            setOnClickListener {
                commitAll()
                resetToggle()
                type(it, "@")
            }
        }, colParams())

        // 入力切替：あ → A → 1 → あ…（定型文を表示中なら直前の入力モードに戻る）
        // 長押し：キーボードの切り替え
        val mk = key("", pal.key).apply {
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                switchMode()
            }
            setOnLongClickListener {
                commitAll()
                imm().showInputMethodPicker()
                true
            }
        }
        col.addView(mk, colParams())
        modeKey = mk
        updateModeKey()

        return col
    }

    // 右列：⌫ ／ ▶ ／ ␣（変換中は「変換」）／ ↵（変換中は「確定」）
    private fun rightColumn(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        col.addView(key("", pal.key).apply {
            background = iconBg(pal.key, dp(8).toFloat(), R.drawable.ic_kb_back, pal.text)
            repeatable(this) { backspace() }
        }, colParams())

        col.addView(key("", pal.key).apply {
            background = iconBg(pal.key, dp(8).toFloat(), R.drawable.ic_kb_right, pal.text)
            repeatable(this) { cursor(KeyEvent.KEYCODE_DPAD_RIGHT) }
        }, colParams())

        val sp = key("", pal.key).apply {
            setOnClickListener { space(it) }
        }
        col.addView(sp, colParams())
        spaceKey = sp

        val ek = key("", pal.accent).apply {
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                enterPressed()
            }
        }
        col.addView(ek, colParams())
        enterKeys.add(ek)

        return col
    }

    private fun colParams() = LinearLayout.LayoutParams(match, 0, 1f).apply {
        setMargins(dp(2), dp(2), dp(2), dp(2))
    }

    // ↶ 元に戻す：入力中なら取り消し、確定直後なら確定を取り消し（変換なら読みに戻す）、
    // それ以外はアプリ側の「元に戻す」（Ctrl+Z）を送る
    private fun undo() {
        stopRepeat()
        if (composing.isNotEmpty()) {
            if (sel >= 0) {
                sel = -1
                showComposing()
                refreshCandBar()
            } else {
                composing.clear()
                resetToggle()
                updateComposing()
            }
            return
        }
        resetToggle()
        val ic = currentInputConnection ?: return
        val last = lastCommit
        val reading = lastReading
        lastCommit = ""
        lastReading = null
        if (last.isNotEmpty() && ic.getTextBeforeCursor(last.length, 0)?.toString() == last) {
            ic.deleteSurroundingText(last.length, 0)
            if (reading != null && tab == TAB_JA) {
                composing.append(reading)
                updateComposing()
            }
            return
        }
        val now = SystemClock.uptimeMillis()
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z, 0, meta))
    }

    // ---------- タブと入力エリア ----------

    private fun render() {
        val box = listBox ?: return
        updateModeKey()

        val phrasesOpen = tab == TAB_PHRASE
        keysRow?.visibility = if (phrasesOpen) View.INVISIBLE else View.VISIBLE
        listScroll?.visibility = if (phrasesOpen) View.VISIBLE else View.GONE
        jaGrid?.visibility = if (tab == TAB_JA) View.VISIBLE else View.GONE
        enGrid?.visibility = if (tab == TAB_EN) View.VISIBLE else View.GONE
        numGrid?.visibility = if (tab == TAB_NUM) View.VISIBLE else View.GONE
        phraseBtn?.background = if (phrasesOpen) {
            iconBg(pal.accent, dp(8).toFloat(), R.drawable.ic_kb_phrase, pal.onAccent)
        } else {
            iconBg(pal.card, dp(8).toFloat(), R.drawable.ic_kb_phrase, pal.sub)
        }

        box.removeAllViews()
        if (!phrasesOpen) return

        if (categories.all { it.phrases.isEmpty() }) {
            box.addView(TextView(this).apply {
                text = "定型文がまだないよ。⚙ から追加してね"
                setTextColor(pal.sub)
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, dp(40), 0, 0)
            })
            return
        }

        // 全カテゴリを見出し付きで、1本の縦スクロールに並べる（定型文は2列）
        for (c in categories) {
            if (c.phrases.isEmpty()) continue
            box.addView(TextView(this).apply {
                text = c.name
                setTextColor(pal.accent)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(4), dp(8), 0, dp(4))
            })
            c.phrases.chunked(2).forEach { pair ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                pair.forEach { ph -> row.addView(phraseCell(ph), cellParams()) }
                if (pair.size == 1) row.addView(View(this), cellParams())
                box.addView(row, LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dp(4) })
            }
        }
    }

    // 定型文パネルの開閉（開くときは入力中の文字を確定してから）
    private fun togglePhrases() {
        if (tab == TAB_PHRASE) {
            backToKeyboard()
            return
        }
        commitAll()
        resetToggle()
        cancelVoice()
        tab = TAB_PHRASE
        render()
        listScroll?.scrollTo(0, 0)
    }

    private fun cellParams() = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
        leftMargin = dp(2)
        rightMargin = dp(2)
    }

    private fun phraseCell(p: Phrase): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = press(pal.key, dp(8).toFloat())
        }
        cell.addView(TextView(this).apply {
            text = p.label()
            setTextColor(pal.text)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (p.title.isNotBlank()) {
            cell.addView(TextView(this).apply {
                text = p.body.replace("\n", " ")
                setTextColor(pal.sub)
                textSize = 10f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        // タップ：入力欄に直接入れる
        cell.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            commitAll()
            resetToggle()
            currentInputConnection?.commitText(p.body, 1)
            lastCommit = p.body
            lastReading = null
        }
        // 長押し：クリップボードにコピー
        cell.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            copy(p.body)
            true
        }
        return cell
    }

    // ---------- フリックキー（共通） ----------
    // タップ＝[0]、左＝[1]、上＝[2]、右＝[3]、下＝[4]
    private fun flickKey(
        chars: Array<String>,
        labelFn: () -> String,
        show: (String) -> String = { it },
        onInput: (String, Boolean) -> Unit
    ): TextView {
        val first = labelFn()
        val k = key(first, pal.key).apply { textSize = if (first.length > 1) 14f else 20f }
        var x0 = 0f
        var y0 = 0f
        val th = dp(18).toFloat()
        fun dir(x: Float, y: Float): Int {
            val dx = x - x0
            val dy = y - y0
            if (abs(dx) < th && abs(dy) < th) return 0
            return if (abs(dx) > abs(dy)) {
                if (dx < 0) 1 else 3
            } else {
                if (dy < 0) 2 else 4
            }
        }
        k.setOnTouchListener { v, e ->
            val tv = v as TextView
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = e.x
                    y0 = e.y
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                MotionEvent.ACTION_MOVE -> {
                    // フリック中は入る文字をキーに表示
                    val d = dir(e.x, e.y)
                    val c = chars[d]
                    tv.text = if (d == 0 || c.isEmpty()) labelFn() else show(c)
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    tv.text = labelFn()
                    val d = dir(e.x, e.y)
                    val c = chars[d]
                    if (c.isNotEmpty()) onInput(c, d == 0)
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    tv.text = labelFn()
                }
            }
            true
        }
        return k
    }

    private fun gridKeyParams() = LinearLayout.LayoutParams(0, match, 1f).apply {
        setMargins(dp(2), dp(2), dp(2), dp(2))
    }

    // ---------- 日本語フリック ----------

    private fun buildJaGrid(): View {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rows = listOf(
            listOf(Kana.A, Kana.KA, Kana.SA),
            listOf(Kana.TA, Kana.NA, Kana.HA),
            listOf(Kana.MA, Kana.YA, Kana.RA)
        )
        for (r in rows) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (chars in r) row.addView(jaKey(chars, chars[0]), gridKeyParams())
            grid.addView(row, LinearLayout.LayoutParams(match, 0, 1f))
        }
        val last = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        last.addView(key("小゛゜", pal.key).apply {
            textSize = 14f
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                modifyLast()
            }
        }, gridKeyParams())
        last.addView(jaKey(Kana.WA, "わ"), gridKeyParams())
        last.addView(jaKey(Kana.PUNCT, "、。?!"), gridKeyParams())
        grid.addView(last, LinearLayout.LayoutParams(match, 0, 1f))
        return grid
    }

    private fun jaKey(chars: Array<String>, label: String): TextView =
        flickKey(chars, { label }) { s, isTap -> inputKana(s, isTap, chars) }

    private fun inputKana(s: String, isTap: Boolean, chars: Array<String>) {
        if (sel >= 0) choose(sel)
        val now = SystemClock.uptimeMillis()
        val opts = chars.filter { it.isNotEmpty() }
        // 同じキーを1秒以内に続けてタップしたら文字を切り替える（あ→い→う…）
        if (isTap && toggleKey === chars && now - toggleTime < 1000 && composing.isNotEmpty()) {
            toggleIdx = (toggleIdx + 1) % opts.size
            composing.setLength(composing.length - 1)
            composing.append(opts[toggleIdx])
        } else {
            composing.append(s)
            toggleIdx = 0
        }
        toggleKey = if (isTap) chars else null
        toggleTime = now
        updateComposing()
    }

    // 小゛゜：最後の文字を 濁点→半濁点→小文字 の順に切り替え
    private fun modifyLast() {
        if (sel >= 0) {
            sel = -1
            showComposing()
        }
        if (composing.isEmpty()) return
        val m = Kana.modify(composing[composing.length - 1]) ?: return
        composing.setCharAt(composing.length - 1, m)
        resetToggle()
        updateComposing()
    }

    private fun resetToggle() {
        toggleKey = null
        toggleIdx = 0
    }

    private fun updateComposing() {
        sel = -1
        cands = if (composing.isEmpty()) emptyList() else dict.candidates(composing.toString())
        showComposing()
        refreshCandBar()
    }

    // 入力欄の下線つき文字を更新（候補選択中は候補＋残りを表示）
    private fun showComposing() {
        val ic = currentInputConnection ?: return
        if (composing.isEmpty()) {
            ic.setComposingText("", 1)
            ic.finishComposingText()
            return
        }
        val c = cands.getOrNull(sel)
        if (c != null) {
            ic.setComposingText(c.word + composing.substring(c.len), 1)
        } else {
            ic.setComposingText(composing, 1)
        }
    }

    private fun choose(i: Int) {
        val c = cands.getOrNull(i) ?: return
        val ic = currentInputConnection ?: return
        val reading = composing.substring(0, c.len)
        ic.commitText(c.word, 1)
        lastCommit = c.word
        lastReading = reading
        dict.learn(reading, c.word, c.segs)
        composing.delete(0, c.len)
        resetToggle()
        updateComposing()
    }

    // 変換中の文字をそのまま確定（候補を選んでいればその候補で）
    private fun commitAll() {
        if (composing.isEmpty()) return
        if (sel >= 0) choose(sel)
        if (composing.isEmpty()) return
        val raw = composing.toString()
        currentInputConnection?.commitText(raw, 1)
        lastCommit = raw
        lastReading = raw
        composing.clear()
        resetToggle()
        updateComposing()
    }

    private fun clearComposingState() {
        composing.clear()
        cands = emptyList()
        sel = -1
        resetToggle()
    }

    private fun refreshCandBar() {
        val cs = candScroll ?: return
        val row = candRow ?: return
        val active = composing.isNotEmpty()
        cs.visibility = if (active) View.VISIBLE else View.GONE
        tabScroll?.visibility = if (active) View.GONE else View.VISIBLE
        for (v in topIcons) v.visibility = if (active) View.GONE else View.VISIBLE

        spaceKey?.apply {
            text = if (active) "変換" else ""
            textSize = 14f
            background = if (active) press(pal.key, dp(8).toFloat())
            else iconBg(pal.key, dp(8).toFloat(), R.drawable.ic_kb_space, pal.text)
        }
        for (ek in enterKeys) {
            ek.text = if (active) "確定" else ""
            ek.textSize = 15f
            ek.background = if (active) press(pal.accent, dp(22).toFloat())
            else iconBg(pal.accent, dp(22).toFloat(), R.drawable.ic_kb_enter, pal.onAccent, 24)
        }

        row.removeAllViews()
        if (!active) return
        if (!dict.ready && cands.size <= 2) {
            row.addView(TextView(this).apply {
                text = "辞書を準備中…"
                textSize = 11f
                setTextColor(pal.sub)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), 0, dp(10), 0)
            }, LinearLayout.LayoutParams(wrap, match))
        }
        val shown = cands.take(40)
        shown.forEachIndexed { i, c ->
            val on = i == sel
            val partial = c.len < composing.length
            val chip = TextView(this).apply {
                text = if (partial) c.word + "…" else c.word
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(dp(10), 0, dp(10), 0)
                setTextColor(if (on) pal.onAccent else pal.text)
                background = press(if (on) pal.accent else pal.card, dp(4).toFloat())
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    choose(i)
                }
            }
            row.addView(chip, LinearLayout.LayoutParams(wrap, match).apply { rightMargin = dp(4) })
        }
        if (sel >= 0) {
            val v = row.getChildAt(row.childCount - shown.size + sel)
            if (v != null) cs.post { cs.smoothScrollTo(maxOf(0, v.left - dp(40)), 0) }
        } else {
            cs.scrollTo(0, 0)
        }
    }

    // ---------- 英語フリック ----------

    private fun buildEnGrid(): View {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (r in Kana.EN_ROWS) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((label, chars) in r) row.addView(enKey(chars, label), gridKeyParams())
            grid.addView(row, LinearLayout.LayoutParams(match, 0, 1f))
        }
        val last = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // a/A タップ：直前に打った文字の大文字・小文字を切り替え（a → A → a）
        //     長押し：ずっと大文字（CAPS）のオン・オフ
        val ck = key("a/A", pal.key).apply {
            textSize = 16f
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                toggleLastCase()
            }
            setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                resetToggle()
                shift = if (shift == 2) 0 else 2
                updateCaseLabels()
                true
            }
        }
        last.addView(ck, gridKeyParams())
        caseKey = ck
        last.addView(enKey(Kana.EN_QUOTE.second, Kana.EN_QUOTE.first), gridKeyParams())
        last.addView(enKey(Kana.EN_PUNCT.second, Kana.EN_PUNCT.first), gridKeyParams())
        grid.addView(last, LinearLayout.LayoutParams(match, 0, 1f))
        return grid
    }

    private fun enKey(chars: Array<String>, label: String): TextView {
        val k = flickKey(
            chars,
            { if (shift > 0) label.uppercase() else label },
            { if (shift > 0) it.uppercase() else it }
        ) { s, isTap -> inputDirect(s, isTap, chars, true) }
        enLabelKeys.add(k)
        k.tag = label
        k.textSize = if (label.length <= 3) 19f else 17f
        return k
    }

    // 直前の1文字が英字なら、大文字・小文字を入れ替える
    private fun toggleLastCase() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)?.toString() ?: return
        if (before.isEmpty()) return
        val ch = before[0]
        if (!ch.isLetter()) return
        val sw = if (ch.isUpperCase()) ch.lowercaseChar() else ch.uppercaseChar()
        if (sw == ch) return
        ic.deleteSurroundingText(1, 0)
        ic.commitText(sw.toString(), 1)
        // 同じキーの連打切り替え中なら、続きもこの大文字・小文字で
        toggleUpper = sw.isUpperCase()
    }

    private fun updateCaseLabels() {
        for (k in enLabelKeys) {
            val base = k.tag as? String ?: continue
            k.text = if (shift > 0) base.uppercase() else base
        }
        caseKey?.apply {
            text = if (shift == 2) "CAPS" else "a/A"
            setTextColor(if (shift > 0) pal.onAccent else pal.text)
            background = press(if (shift > 0) pal.accent else pal.key, dp(8).toFloat())
        }
    }

    // ---------- 数字フリック ----------

    private fun buildNumGrid(): View {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (r in Kana.NUM_ROWS) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((label, chars) in r) {
                // 数字は「11」なども打てるように連続タップの切り替えはしない
                row.addView(flickKey(chars, { label }) { s, _ -> inputDirect(s, false, chars, false) }, gridKeyParams())
            }
            grid.addView(row, LinearLayout.LayoutParams(match, 0, 1f))
        }
        return grid
    }

    // 英語・数字：変換なしでそのまま入力
    private fun inputDirect(s: String, isTap: Boolean, chars: Array<String>, caseAware: Boolean) {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        val opts = chars.filter { it.isNotEmpty() }
        if (isTap && toggleKey === chars && now - toggleTime < 1000) {
            // 同じキーの連続タップ：直前の1文字を次の文字に置き換える（a→b→c）
            toggleIdx = (toggleIdx + 1) % opts.size
            val next = opts[toggleIdx]
            ic.deleteSurroundingText(1, 0)
            ic.commitText(if (toggleUpper) next.uppercase() else next, 1)
        } else {
            val upper = caseAware && shift > 0
            ic.commitText(if (upper) s.uppercase() else s, 1)
            toggleIdx = 0
            toggleUpper = upper
        }
        toggleKey = if (isTap) chars else null
        toggleTime = now
    }

    // 定型文から、直前の入力モード（あ/A/1）のキーボードに戻る
    private fun backToKeyboard() {
        tab = inputMode
        userTab = inputMode
        render()
        tabScroll?.scrollTo(0, 0)
    }

    private fun switchMode() {
        commitAll()
        resetToggle()
        tab = if (tab >= TAB_PHRASE) inputMode else (tab + 1) % TAB_PHRASE
        inputMode = tab
        userTab = tab
        shift = 0
        updateCaseLabels()
        render()
    }

    // 「あ A 1」を並べて、今のモードだけオレンジで表示
    private fun updateModeKey() {
        val mk = modeKey ?: return
        val labels = listOf("あ", "A", "1")
        val sb = SpannableStringBuilder()
        labels.forEachIndexed { i, s ->
            if (i > 0) sb.append(" ")
            val start = sb.length
            sb.append(s)
            val on = i == inputMode
            sb.setSpan(
                ForegroundColorSpan(if (on) pal.accent else pal.sub),
                start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            sb.setSpan(
                RelativeSizeSpan(if (on) 1.25f else 0.85f),
                start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            if (on) sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        mk.textSize = 14f
        mk.text = sb
    }

    private fun key(label: String, color: Int): TextView = TextView(this).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(if (color == pal.accent) pal.onAccent else pal.text)
        background = press(color, dp(8).toFloat())
    }

    private fun keyParams(weight: Float) = LinearLayout.LayoutParams(0, match, weight).apply {
        leftMargin = dp(2)
        rightMargin = dp(2)
    }

    // 押した瞬間に1回、0.4秒押し続けたら連続実行
    private fun repeatable(v: View, action: () -> Unit) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    stopRepeat()
                    view.isPressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    action()
                    repeatKey = view
                    repeatAction = action
                    handler.postDelayed(repeater, 400)
                }
                MotionEvent.ACTION_MOVE -> {
                    // 指がキーの外に出たら止める
                    if (e.x < 0 || e.y < 0 || e.x > view.width || e.y > view.height) stopRepeat()
                }
                else -> stopRepeat()
            }
            true
        }
    }

    private fun stopRepeat() {
        handler.removeCallbacks(repeater)
        repeatKey?.isPressed = false
        repeatKey = null
        repeatAction = null
    }

    // ---------- 音声入力 → AI添削 → 自動入力 ----------

    private fun buildVoicePanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = Ui.round(pal.card, dp(8).toFloat())
            visibility = View.GONE
            isClickable = true // 下のキーに触れないようにする
        }
        val st = TextView(this).apply {
            textSize = 13f
            setTextColor(pal.accent)
            typeface = Typeface.DEFAULT_BOLD
        }
        panel.addView(st)
        val sv = ScrollView(this)
        val body = TextView(this).apply {
            textSize = 14f
            setTextColor(pal.text)
            setPadding(0, dp(4), 0, dp(4))
        }
        sv.addView(body)
        panel.addView(sv, LinearLayout.LayoutParams(match, 0, 1f))

        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val sub = key("キャンセル", pal.key).apply {
            textSize = 13f
            setOnClickListener { onVoiceSub() }
        }
        val main = key("完了", pal.accent).apply {
            textSize = 14f
            setOnClickListener { onVoiceMain() }
        }
        btns.addView(sub, LinearLayout.LayoutParams(0, match, 1f).apply { rightMargin = dp(4) })
        btns.addView(main, LinearLayout.LayoutParams(0, match, 1.6f).apply { leftMargin = dp(4) })
        panel.addView(btns, LinearLayout.LayoutParams(match, dp(36)))

        voicePanel = panel
        voiceStatus = st
        voiceBody = body
        voiceMainBtn = main
        voiceSubBtn = sub
        return panel
    }

    private fun setStage(stage: Int, status: String, body: String?) {
        voiceStage = stage
        voicePanel?.visibility = if (stage == 0) View.GONE else View.VISIBLE
        voiceStatus?.text = status
        if (body != null) voiceBody?.text = body
        val main = voiceMainBtn ?: return
        val sub = voiceSubBtn ?: return
        when (stage) {
            1 -> {
                main.text = "添削して入力"; main.visibility = View.VISIBLE
                sub.text = "キャンセル"; sub.visibility = View.VISIBLE
            }
            2 -> {
                main.text = "添削中…"; main.visibility = View.VISIBLE
                sub.text = "キャンセル"; sub.visibility = View.VISIBLE
            }
            3 -> {
                main.text = "閉じる"; main.visibility = View.VISIBLE
                sub.text = "元の文に戻す"; sub.visibility = View.VISIBLE
            }
            else -> {
                main.text = "閉じる"; main.visibility = View.VISIBLE
                sub.visibility = View.GONE
            }
        }
    }

    private fun onVoiceMain() {
        when (voiceStage) {
            1 -> stopVoice()
            2 -> {}
            else -> setStage(0, "", "")
        }
    }

    private fun onVoiceSub() {
        when (voiceStage) {
            1, 2 -> cancelVoice()
            3 -> revertVoice()
            else -> setStage(0, "", "")
        }
    }

    private fun startVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("マイクが許可されてないよ。⚙の設定画面で許可してね")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("この端末では音声認識が使えないみたい")
            return
        }
        commitAll()
        resetToggle()
        cancelVoice()
        voiceBuf.clear()
        voiceStopping = false
        voiceFinished = false
        setStage(1, "🎤 準備中…", "")
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        r.setRecognitionListener(voiceListener)
        recognizer = r
        listen()
    }

    private fun recogIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
    }

    private fun listen() {
        val r = recognizer ?: return
        voiceListening = true
        try {
            r.startListening(recogIntent())
        } catch (e: Exception) {
            voiceListening = false
            voiceFail("音声認識を開始できなかったよ")
        }
    }

    private fun best(b: Bundle?): String =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""

    // 区切りで認識が止まっても「完了」を押すまで聞き続ける
    private val voiceListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (voiceStage == 1 && !voiceStopping) voiceStatus?.text = "🎤 聞いています…（話し終わったら「完了」）"
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (voiceStage != 1) return
            voiceBody?.text = voiceBuf.toString() + best(partialResults)
        }

        override fun onResults(results: Bundle?) {
            voiceListening = false
            voiceBuf.append(best(results))
            if (voiceStage == 1) voiceBody?.text = voiceBuf.toString()
            if (voiceStopping) {
                finishVoice()
            } else {
                handler.post { if (voiceStage == 1 && !voiceStopping) listen() }
            }
        }

        override fun onError(error: Int) {
            voiceListening = false
            if (voiceStage != 1) return
            if (voiceStopping) {
                finishVoice()
                return
            }
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    handler.postDelayed({ if (voiceStage == 1 && !voiceStopping) listen() }, 250)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    voiceFail("マイクの許可がないよ。⚙の設定画面で許可してね")
                else ->
                    if (voiceBuf.isNotBlank()) finishVoice() else voiceFail("音声認識エラー（$error）")
            }
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // 「完了」：聞き取りを止めて添削へ
    private fun stopVoice() {
        voiceStopping = true
        voiceStatus?.text = "🎤 聞き取りを終了中…"
        if (voiceListening) {
            try {
                recognizer?.stopListening()
            } catch (e: Exception) {
            }
            // 結果が返ってこないときの保険（キャンセル・やり直し後は何もしない）
            val t = voiceToken
            handler.postDelayed({ if (t == voiceToken) finishVoice() }, 2500)
        } else {
            finishVoice()
        }
    }

    private fun finishVoice() {
        if (voiceFinished || voiceStage != 1) return
        voiceFinished = true
        destroyRecognizer()
        val raw = voiceBuf.toString().trim()
        if (raw.isEmpty()) {
            setStage(4, "聞き取れなかったよ", "")
            return
        }
        val apiKey = prefs().getString("gemini_key", "") ?: ""
        if (apiKey.isBlank()) {
            insertVoice(raw, raw)
            setStage(4, "APIキーが未設定なので、そのまま入力したよ（⚙で設定）", raw)
            return
        }
        val model = prefs().getString("gemini_model", Gemini.DEFAULT_MODEL) ?: Gemini.DEFAULT_MODEL
        val token = ++voiceToken
        setStage(2, "✨ AIで添削中…", raw)
        Thread {
            val result = try {
                Result.success(Gemini.correct(apiKey, model, raw))
            } catch (e: Exception) {
                Result.failure<Gemini.Result>(e)
            }
            handler.post {
                if (token != voiceToken || voiceStage != 2) return@post
                result.fold(
                    onSuccess = { r ->
                        insertVoice(r.text, raw)
                        val note = if (r.model != model.trim().ifEmpty { Gemini.DEFAULT_MODEL }) "（混雑のため ${r.model} で添削）" else ""
                        setStage(3, "✅ 添削して入力したよ$note", r.text)
                    },
                    onFailure = { e ->
                        insertVoice(raw, raw)
                        setStage(4, "⚠ ${e.message ?: "AIの添削に失敗"}\n元の文をそのまま入力したよ", raw)
                    }
                )
            }
        }.start()
    }

    private fun insertVoice(text: String, raw: String) {
        currentInputConnection?.commitText(text, 1)
        lastCommit = text
        lastReading = null
        lastInserted = text
        lastRaw = raw
    }

    // 「元の文に戻す」：直前に入れた添削後の文を、添削前の文に置き換える
    private fun revertVoice() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(lastInserted.length, 0)?.toString()
        if (lastInserted.isNotEmpty() && before == lastInserted) {
            ic.deleteSurroundingText(lastInserted.length, 0)
            ic.commitText(lastRaw, 1)
            lastInserted = lastRaw
            setStage(4, "元の文に戻したよ", lastRaw)
        } else {
            toast("戻せなかったよ（カーソルが動いたかも）")
        }
    }

    private fun voiceFail(msg: String) {
        destroyRecognizer()
        voiceFinished = true
        setStage(4, "⚠ $msg", null)
    }

    private fun cancelVoice() {
        voiceToken++
        destroyRecognizer()
        voiceListening = false
        voiceStopping = false
        if (voiceStage != 0) setStage(0, "", "")
    }

    private fun destroyRecognizer() {
        val r = recognizer ?: return
        recognizer = null
        try {
            r.cancel()
            r.destroy()
        } catch (e: Exception) {
        }
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    // ---------- 動作 ----------

    private fun type(v: View, s: String) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        currentInputConnection?.commitText(s, 1)
    }

    private fun space(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (composing.isNotEmpty()) {
            if (cands.isEmpty()) return
            sel = (sel + 1) % minOf(cands.size, 40)
            resetToggle()
            showComposing()
            refreshCandBar()
        } else {
            resetToggle()
            currentInputConnection?.commitText(" ", 1)
        }
    }

    private fun enterPressed() {
        if (composing.isNotEmpty()) {
            if (sel >= 0) choose(sel) else commitAll()
            return
        }
        resetToggle()
        enter()
    }

    private fun backspace() {
        if (composing.isNotEmpty()) {
            if (sel >= 0) {
                sel = -1
                showComposing()
                refreshCandBar()
            } else {
                composing.setLength(composing.length - 1)
                resetToggle()
                updateComposing()
            }
            return
        }
        resetToggle()
        deleteOne()
    }

    private fun cursor(keyCode: Int) {
        // 同じキー連打中の → は「次の文字へ進む」だけ（日本語・英語）
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && toggleKey != null && sel < 0 &&
            (composing.isNotEmpty() || tab == TAB_EN)
        ) {
            resetToggle()
            return
        }
        resetToggle()
        if (composing.isNotEmpty()) commitAll()
        sendDownUpKeyEvents(keyCode)
    }

    private fun imm(): InputMethodManager =
        getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private fun openSettings() {
        stopRepeat()
        commitAll()
        requestHideSelf(0)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun deleteOne() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
    }

    private fun enter() {
        val ic = currentInputConnection ?: return
        val ei = currentInputEditorInfo
        val action = (ei?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noAction = ((ei?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            ic.commitText("\n", 1)
        }
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("定型文", text))
        // Android 13以降はOS側でコピー通知が出るので重ねない
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "コピーしたよ", Toast.LENGTH_SHORT).show()
        }
    }
}
