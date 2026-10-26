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
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class KookyKeyboardService : InputMethodService() {

    private lateinit var store: PhraseStore
    private var categories: MutableList<Category> = mutableListOf()
    private var current = 0

    // 片手モード 0=オフ 1=右寄せ 2=左寄せ
    private var oneHand = 0

    private var panel: LinearLayout? = null
    private var tabsRow: LinearLayout? = null
    private var listBox: LinearLayout? = null
    private var listScroll: ScrollView? = null
    private var handKey: TextView? = null

    private val handler = Handler(Looper.getMainLooper())
    private val repeatDelete = object : Runnable {
        override fun run() {
            deleteOne()
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
        categories = store.load()
        oneHand = prefs().getInt("one_hand", 0)

        // 外枠（画面幅いっぱい）
        val root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }

        // 中身（片手モードのときは幅を縮めて左右に寄せる）
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        root.addView(p, FrameLayout.LayoutParams(match, wrap))
        panel = p

        // 上段：カテゴリのタブ ＋ 片手キー
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tabScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabScroll.addView(tabs)
        top.addView(tabScroll, LinearLayout.LayoutParams(0, match, 1f))
        tabsRow = tabs

        val hk = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                oneHand = (oneHand + 1) % 3
                prefs().edit().putInt("one_hand", oneHand).apply()
                applyOneHand()
            }
        }
        top.addView(hk, LinearLayout.LayoutParams(dp(46), match).apply { leftMargin = dp(4) })
        handKey = hk
        p.addView(top, LinearLayout.LayoutParams(match, dp(30)))

        // 中段：定型文ボタン一覧（縦スクロール）
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(box)
        p.addView(scroll, LinearLayout.LayoutParams(match, dp(134)).apply { topMargin = dp(4) })
        listBox = box
        listScroll = scroll

        // 下段のキー
        p.addView(buildBottomRow(), LinearLayout.LayoutParams(match, dp(38)).apply { topMargin = dp(4) })

        applyOneHand()
        render()
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // 設定画面で編集した内容を毎回読み直す
        categories = store.load()
        if (current >= categories.size) current = 0
        applyOneHand()
        render()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        handler.removeCallbacks(repeatDelete)
        super.onFinishInputView(finishingInput)
    }

    // ---------- 片手モード ----------

    private fun applyOneHand() {
        val p = panel ?: return
        val lp = p.layoutParams as FrameLayout.LayoutParams
        if (oneHand == 0) {
            lp.width = match
            lp.gravity = Gravity.NO_GRAVITY
        } else {
            lp.width = (resources.displayMetrics.widthPixels * 0.78f).toInt()
            lp.gravity = if (oneHand == 1) Gravity.END else Gravity.START
        }
        p.layoutParams = lp

        handKey?.apply {
            text = when (oneHand) {
                1 -> "右手"
                2 -> "左手"
                else -> "片手"
            }
            val on = oneHand != 0
            setTextColor(if (on) Ui.BG else Ui.SUB)
            typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            background = Ui.pressable(if (on) Ui.ORANGE else Ui.CARD, dp(15).toFloat())
        }
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
                background = Ui.pressable(if (selected) Ui.ORANGE else Ui.CARD, dp(15).toFloat())
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

        // @ 半角 / ＠ 全角（メンション用）
        row.addView(key("@", Ui.KEY).apply {
            setOnClickListener { type(it, "@") }
        }, keyParams(1f))
        row.addView(key("＠", Ui.KEY).apply {
            setOnClickListener { type(it, "＠") }
        }, keyParams(1f))

        // スペース
        row.addView(key("空白", Ui.KEY).apply {
            textSize = 12f
            setOnClickListener { type(it, " ") }
        }, keyParams(1.6f))

        // ⌫ 押しっぱなしで連続削除
        row.addView(key("⌫", Ui.KEY).apply {
            setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        deleteOne()
                        handler.postDelayed(repeatDelete, 400)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        handler.removeCallbacks(repeatDelete)
                    }
                }
                true
            }
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

    // ---------- 動作 ----------

    private fun type(v: View, s: String) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        currentInputConnection?.commitText(s, 1)
    }

    private fun imm(): InputMethodManager =
        getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private fun switchBack() {
        val switched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToPreviousInputMethod()
        } else {
            false
        }
        if (!switched) imm().showInputMethodPicker()
    }

    private fun openSettings() {
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
