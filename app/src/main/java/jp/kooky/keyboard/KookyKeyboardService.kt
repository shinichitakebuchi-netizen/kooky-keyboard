package jp.kooky.keyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
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
    private var leftArrow: View? = null
    private var rightArrow: View? = null
    private var leftSide: View? = null
    private var rightSide: View? = null
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
    private val enterKeys = ArrayList<TextView>()

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
    }

    override fun onCreateInputView(): View {
        stopRepeat()
        categories = store.load()
        oneHand = prefs().getInt("one_hand", 0)
        enLabelKeys.clear()
        enterKeys.clear()

        // 外枠：[左の◀▶帯][キーボード本体][右の◀▶帯]
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.BG)
        }
        val stripWidth = (resources.displayMetrics.widthPixels * 0.22f).toInt()

        val ls = stripKey()
        root.addView(ls, LinearLayout.LayoutParams(stripWidth, match))
        leftStrip = ls

        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        root.addView(p, LinearLayout.LayoutParams(0, wrap, 1f))

        val rs = stripKey()
        root.addView(rs, LinearLayout.LayoutParams(stripWidth, match))
        rightStrip = rs

        // 上段：[◀][タブ or 変換候補][▶]
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val la = arrowKey("◀") { setOneHand(2) }
        top.addView(la, LinearLayout.LayoutParams(dp(30), match).apply { rightMargin = dp(4) })
        leftArrow = la

        val ts = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ts.addView(tabs)
        top.addView(ts, LinearLayout.LayoutParams(0, match, 1f))
        tabScroll = ts
        tabsRow = tabs

        val cs = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
        }
        val cr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cs.addView(cr)
        top.addView(cs, LinearLayout.LayoutParams(0, match, 1f))
        candScroll = cs
        candRow = cr

        val ra = arrowKey("▶") { setOneHand(1) }
        top.addView(ra, LinearLayout.LayoutParams(dp(30), match).apply { leftMargin = dp(4) })
        rightArrow = ra

        p.addView(top, LinearLayout.LayoutParams(match, dp(32)))

        // 本体：[サイド列(左寄せ時)][入力エリア＋下段キー][サイド列(通常・右寄せ時)]
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val lside = sideColumn()
        body.addView(lside, LinearLayout.LayoutParams(dp(54), match).apply { rightMargin = dp(4) })
        leftSide = lside

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 入力エリア：定型文 / 日本語 / 英語 / 数字 を切り替え
        val area = FrameLayout(this)
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(box)
        area.addView(scroll, FrameLayout.LayoutParams(match, match))
        listBox = box
        listScroll = scroll

        val jg = buildJaGrid()
        area.addView(jg, FrameLayout.LayoutParams(match, match))
        jaGrid = jg

        val eg = buildEnGrid()
        area.addView(eg, FrameLayout.LayoutParams(match, match))
        enGrid = eg

        val ng = buildNumGrid()
        area.addView(ng, FrameLayout.LayoutParams(match, match))
        numGrid = ng

        content.addView(area, LinearLayout.LayoutParams(match, dp(152)))
        content.addView(buildBottomRow(), LinearLayout.LayoutParams(match, dp(46)).apply { topMargin = dp(4) })
        body.addView(content, LinearLayout.LayoutParams(0, wrap, 1f))

        val rside = sideColumn()
        body.addView(rside, LinearLayout.LayoutParams(dp(54), match).apply { leftMargin = dp(4) })
        rightSide = rside

        p.addView(body, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(4) })

        applyOneHand()
        render()
        refreshCandBar()
        updateCaseLabels()
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        stopRepeat()
        clearComposingState()
        dict.prepare()
        // 設定画面で編集した内容を毎回読み直す
        categories = store.load()
        if (!restarting) tab = startTab(info)
        if (tab >= categories.size + TAB_PHRASE) tab = TAB_JA
        shift = 0
        applyOneHand()
        render()
        refreshCandBar()
        updateCaseLabels()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        stopRepeat()
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
        stopRepeat()
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
        val type = info?.inputType ?: return userTab
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

    // 0=通常（◀/▶を両端、サイド列は右） 1=右寄せ（左に◀▶帯） 2=左寄せ（右に◀▶帯、サイド列は左）
    private fun applyOneHand() {
        leftStrip?.visibility = if (oneHand == 1) View.VISIBLE else View.GONE
        rightStrip?.visibility = if (oneHand == 2) View.VISIBLE else View.GONE
        leftArrow?.visibility = if (oneHand == 0) View.VISIBLE else View.GONE
        rightArrow?.visibility = if (oneHand == 0) View.VISIBLE else View.GONE
        leftSide?.visibility = if (oneHand == 2) View.VISIBLE else View.GONE
        rightSide?.visibility = if (oneHand == 2) View.GONE else View.VISIBLE
    }

    private fun arrowKey(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(Ui.SUB)
        background = Ui.pressable(Ui.CARD, dp(4).toFloat())
        setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        }
    }

    private fun stripKey(): TextView = TextView(this).apply {
        text = "◀▶"
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(Ui.SUB)
        background = Ui.pressable(Ui.BG, 0f)
        visibility = View.GONE
        setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            setOneHand(0)
        }
    }

    // サイド列：上から @（小さめ）、⌫、↵（大きめ）
    private fun sideColumn(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        col.addView(key("@", Ui.KEY).apply {
            textSize = 14f
            setOnClickListener {
                commitAll()
                resetToggle()
                type(it, "@")
            }
        }, sideParams(0.6f))

        // ⌫ 押しっぱなしで連続削除
        col.addView(key("⌫", Ui.KEY).apply {
            textSize = 18f
            repeatable(this) { backspace() }
        }, sideParams(1f))

        // ↵ 改行 / 送信（変換中は「確定」）
        val ek = key("↵", Ui.ORANGE).apply {
            textSize = 22f
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                enterPressed()
            }
        }
        col.addView(ek, sideParams(1.6f))
        enterKeys.add(ek)

        return col
    }

    private fun sideParams(weight: Float) = LinearLayout.LayoutParams(match, 0, weight).apply {
        topMargin = dp(2)
        bottomMargin = dp(2)
    }

    // ---------- タブと入力エリア ----------

    private fun render() {
        val tabs = tabsRow ?: return
        val box = listBox ?: return

        tabs.removeAllViews()
        val names = listOf("あ", "A", "1") + categories.map { it.name }
        names.forEachIndexed { i, name ->
            val selected = i == tab
            val isInput = i < TAB_PHRASE
            val chip = TextView(this).apply {
                text = name
                textSize = if (isInput) 15f else 13f
                gravity = Gravity.CENTER
                setPadding(dp(if (isInput) 13 else 12), 0, dp(if (isInput) 13 else 12), 0)
                setTextColor(if (selected) Ui.BG else Ui.TEXT)
                typeface = if (selected || isInput) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                background = Ui.pressable(
                    if (selected) Ui.ORANGE else if (isInput) Ui.KEY else Ui.CARD,
                    dp(4).toFloat()
                )
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    commitAll()
                    resetToggle()
                    tab = i
                    userTab = i
                    render()
                    listScroll?.scrollTo(0, 0)
                }
            }
            tabs.addView(chip, LinearLayout.LayoutParams(wrap, match).apply { rightMargin = dp(5) })
        }

        jaGrid?.visibility = if (tab == TAB_JA) View.VISIBLE else View.GONE
        enGrid?.visibility = if (tab == TAB_EN) View.VISIBLE else View.GONE
        numGrid?.visibility = if (tab == TAB_NUM) View.VISIBLE else View.GONE
        listScroll?.visibility = if (tab >= TAB_PHRASE) View.VISIBLE else View.GONE

        box.removeAllViews()
        if (tab < TAB_PHRASE) return
        val phrases = categories.getOrNull(tab - TAB_PHRASE)?.phrases ?: mutableListOf()
        if (phrases.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "定型文がまだないよ。⚙ から追加してね"
                setTextColor(Ui.SUB)
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, dp(30), 0, 0)
            })
            return
        }

        // 2列で並べる
        phrases.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { p -> row.addView(phraseCell(p), cellParams()) }
            if (pair.size == 1) row.addView(View(this), cellParams())
            box.addView(row, LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dp(4) })
        }
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
            background = Ui.pressable(Ui.KEY, dp(8).toFloat())
        }
        cell.addView(TextView(this).apply {
            text = p.label()
            setTextColor(Ui.TEXT)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (p.title.isNotBlank()) {
            cell.addView(TextView(this).apply {
                text = p.body.replace("\n", " ")
                setTextColor(Ui.SUB)
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
        val k = key(first, Ui.KEY).apply { textSize = if (first.length > 1) 14f else 20f }
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
        last.addView(key("小゛゜", Ui.KEY).apply {
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
        dict.learn(reading, c.word)
        composing.delete(0, c.len)
        resetToggle()
        updateComposing()
    }

    // 変換中の文字をそのまま確定（候補を選んでいればその候補で）
    private fun commitAll() {
        if (composing.isEmpty()) return
        if (sel >= 0) choose(sel)
        if (composing.isEmpty()) return
        currentInputConnection?.commitText(composing.toString(), 1)
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

        spaceKey?.text = if (active) "変換" else "スペース"
        for (ek in enterKeys) {
            ek.text = if (active) "確定" else "↵"
            ek.textSize = if (active) 15f else 22f
        }

        row.removeAllViews()
        if (!active) return
        if (!dict.ready && cands.size <= 2) {
            row.addView(TextView(this).apply {
                text = "辞書を準備中…"
                textSize = 11f
                setTextColor(Ui.SUB)
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
                setTextColor(if (on) Ui.BG else Ui.TEXT)
                background = Ui.pressable(if (on) Ui.ORANGE else Ui.CARD, dp(4).toFloat())
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
        // a/A：小文字 → 次の1文字だけ大文字 → ずっと大文字 → 小文字
        val ck = key("a/A", Ui.KEY).apply {
            textSize = 14f
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                resetToggle()
                shift = (shift + 1) % 3
                updateCaseLabels()
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
        return k
    }

    private fun updateCaseLabels() {
        for (k in enLabelKeys) {
            val base = k.tag as? String ?: continue
            k.text = if (shift > 0) base.uppercase() else base
        }
        caseKey?.apply {
            text = when (shift) {
                1 -> "A"
                2 -> "CAPS"
                else -> "a/A"
            }
            setTextColor(if (shift > 0) Ui.BG else Ui.TEXT)
            background = Ui.pressable(if (shift > 0) Ui.ORANGE else Ui.KEY, dp(8).toFloat())
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
            if (caseAware && shift == 1) {
                shift = 0
                updateCaseLabels()
            }
        }
        toggleKey = if (isTap) chars else null
        toggleTime = now
    }

    // ---------- 下段のキー ----------

    private fun buildBottomRow(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        // ⚙ タップ：定型文の編集画面 / 長押し：キーボードの切り替え
        row.addView(key("⚙", Ui.KEY).apply {
            textSize = 18f
            setOnClickListener { openSettings() }
            setOnLongClickListener {
                commitAll()
                imm().showInputMethodPicker()
                true
            }
        }, keyParams(1f))

        // ← → カーソル移動（押しっぱなしで連続）
        row.addView(key("←", Ui.KEY).apply {
            textSize = 18f
            repeatable(this) { cursor(KeyEvent.KEYCODE_DPAD_LEFT) }
        }, keyParams(1f))
        row.addView(key("→", Ui.KEY).apply {
            textSize = 18f
            repeatable(this) { cursor(KeyEvent.KEYCODE_DPAD_RIGHT) }
        }, keyParams(1f))

        // スペース（変換中は「変換」：次の候補へ）
        val sp = key("スペース", Ui.KEY).apply {
            textSize = 13f
            setOnClickListener { space(it) }
        }
        row.addView(sp, keyParams(2.4f))
        spaceKey = sp

        return row
    }

    private fun key(label: String, color: Int): TextView = TextView(this).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(if (color == Ui.ORANGE) Ui.BG else Ui.TEXT)
        background = Ui.pressable(color, dp(8).toFloat())
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
