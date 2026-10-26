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
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class KookyKeyboardService : InputMethodService() {

    private lateinit var store: PhraseStore
    private var categories: MutableList<Category> = mutableListOf()
    private var current = 0

    // 片手モード 0=通常 1=右寄せ 2=左寄せ
    private var oneHand = 0

    private var leftStrip: View? = null
    private var rightStrip: View? = null
    private var leftArrow: View? = null
    private var rightArrow: View? = null
    private var leftSide: View? = null
    private var rightSide: View? = null
    private var tabsRow: LinearLayout? = null
    private var listBox: LinearLayout? = null
    private var listScroll: ScrollView? = null

    // ---------- 長押しリピート（⌫・カーソル移動） ----------
    // 押しているキーが「押下中」かつ「画面に出ている」間だけ繰り返す。
    // 指を離した合図を取りこぼしても勝手に動き続けないようにする。
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
    }

    override fun onCreateInputView(): View {
        stopRepeat()
        categories = store.load()
        oneHand = prefs().getInt("one_hand", 0)

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

        // 上段：[◀][カテゴリのタブ][▶]
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val la = arrowKey("◀") { setOneHand(2) }
        top.addView(la, LinearLayout.LayoutParams(dp(30), match).apply { rightMargin = dp(4) })
        leftArrow = la

        val tabScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabScroll.addView(tabs)
        top.addView(tabScroll, LinearLayout.LayoutParams(0, match, 1f))
        tabsRow = tabs

        val ra = arrowKey("▶") { setOneHand(1) }
        top.addView(ra, LinearLayout.LayoutParams(dp(30), match).apply { leftMargin = dp(4) })
        rightArrow = ra

        p.addView(top, LinearLayout.LayoutParams(match, dp(30)))

        // 本体：[＠列(左寄せ時)][定型文＋下段キー][＠列(通常・右寄せ時)]
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val lside = sideColumn()
        body.addView(lside, LinearLayout.LayoutParams(dp(40), match).apply { rightMargin = dp(4) })
        leftSide = lside

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(box)
        content.addView(scroll, LinearLayout.LayoutParams(match, dp(134)))
        listBox = box
        listScroll = scroll
        content.addView(buildBottomRow(), LinearLayout.LayoutParams(match, dp(38)).apply { topMargin = dp(4) })
        body.addView(content, LinearLayout.LayoutParams(0, wrap, 1f))

        val rside = sideColumn()
        body.addView(rside, LinearLayout.LayoutParams(dp(40), match).apply { leftMargin = dp(4) })
        rightSide = rside

        p.addView(body, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(4) })

        applyOneHand()
        render()
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        stopRepeat()
        // 設定画面で編集した内容を毎回読み直す
        categories = store.load()
        if (current >= categories.size) current = 0
        applyOneHand()
        render()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        stopRepeat()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        stopRepeat()
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

    // ---------- 片手モード ----------

    private fun setOneHand(mode: Int) {
        oneHand = mode
        prefs().edit().putInt("one_hand", oneHand).apply()
        applyOneHand()
    }

    // 0=通常（◀/▶を両端、＠列は右） 1=右寄せ（左に◀▶帯、＠列は右） 2=左寄せ（右に◀▶帯、＠列は左）
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

    // サイドの＠列（半角@・全角＠）
    private fun sideColumn(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(key("@", Ui.KEY).apply {
            setOnClickListener { type(it, "@") }
        }, LinearLayout.LayoutParams(match, 0, 1f).apply { bottomMargin = dp(2) })
        col.addView(key("＠", Ui.KEY).apply {
            setOnClickListener { type(it, "＠") }
        }, LinearLayout.LayoutParams(match, 0, 1f).apply { topMargin = dp(2) })
        return col
    }

    // ---------- 描画 ----------

    private fun render() {
        val tabs = tabsRow ?: return
        val box = listBox ?: return

        tabs.removeAllViews()
        categories.forEachIndexed { i, c ->
            val selected = i == current
            val chip = TextView(this).apply {
                text = c.name
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                setTextColor(if (selected) Ui.BG else Ui.TEXT)
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                background = Ui.pressable(if (selected) Ui.ORANGE else Ui.CARD, dp(4).toFloat())
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    current = i
                    render()
                    listScroll?.scrollTo(0, 0)
                }
            }
            tabs.addView(chip, LinearLayout.LayoutParams(wrap, match).apply { rightMargin = dp(5) })
        }

        box.removeAllViews()
        val phrases = categories.getOrNull(current)?.phrases ?: mutableListOf()
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

    // ---------- 下段のキー ----------

    private fun buildBottomRow(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        // 🌐 タップ：前のキーボード（Samsungキーボード等）に戻る / 長押し：キーボード選択
        row.addView(key("🌐", Ui.KEY).apply {
            setOnClickListener { switchBack() }
            setOnLongClickListener { imm().showInputMethodPicker(); true }
        }, keyParams(1f))

        // ⚙ 定型文の編集画面を開く
        row.addView(key("⚙", Ui.KEY).apply {
            setOnClickListener { openSettings() }
        }, keyParams(1f))

        // ← → カーソル移動（押しっぱなしで連続）
        row.addView(key("←", Ui.KEY).apply {
            repeatable(this) { moveCursor(KeyEvent.KEYCODE_DPAD_LEFT) }
        }, keyParams(1f))
        row.addView(key("→", Ui.KEY).apply {
            repeatable(this) { moveCursor(KeyEvent.KEYCODE_DPAD_RIGHT) }
        }, keyParams(1f))

        // スペース（半角）
        row.addView(key("スペース", Ui.KEY).apply {
            textSize = 11f
            setOnClickListener { type(it, " ") }
        }, keyParams(1.8f))

        // ⌫ 押しっぱなしで連続削除
        row.addView(key("⌫", Ui.KEY).apply {
            repeatable(this) { deleteOne() }
        }, keyParams(1.2f))

        // ↵ 改行 / 送信
        row.addView(key("↵", Ui.ORANGE).apply {
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                enter()
            }
        }, keyParams(1.2f))

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

    private fun moveCursor(keyCode: Int) {
        sendDownUpKeyEvents(keyCode)
    }

    private fun imm(): InputMethodManager =
        getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private fun switchBack() {
        stopRepeat()
        val switched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToPreviousInputMethod()
        } else {
            false
        }
        if (!switched) imm().showInputMethodPicker()
    }

    private fun openSettings() {
        stopRepeat()
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
