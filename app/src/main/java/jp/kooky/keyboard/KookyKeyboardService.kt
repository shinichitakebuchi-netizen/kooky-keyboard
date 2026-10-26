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

    private var tabsRow: LinearLayout? = null
    private var listBox: LinearLayout? = null
    private var listScroll: ScrollView? = null

    private val handler = Handler(Looper.getMainLooper())
    private val repeatDelete = object : Runnable {
        override fun run() {
            deleteOne()
            handler.postDelayed(this, 60)
        }
    }

    private val match = ViewGroup.LayoutParams.MATCH_PARENT

    override fun onCreate() {
        super.onCreate()
        store = PhraseStore(this)
    }

    override fun onCreateInputView(): View {
        categories = store.load()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        // カテゴリのタブ（横スクロール）
        val tabScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabScroll.addView(tabs)
        root.addView(tabScroll, LinearLayout.LayoutParams(match, dp(40)))
        tabsRow = tabs

        // 定型文ボタン一覧（縦スクロール）
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(box)
        root.addView(scroll, LinearLayout.LayoutParams(match, dp(210)).apply { topMargin = dp(6) })
        listBox = box
        listScroll = scroll

        // 下段のキー
        root.addView(buildBottomRow(), LinearLayout.LayoutParams(match, dp(48)).apply { topMargin = dp(6) })

        render()
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // 設定画面で編集した内容を毎回読み直す
        categories = store.load()
        if (current >= categories.size) current = 0
        render()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        handler.removeCallbacks(repeatDelete)
        super.onFinishInputView(finishingInput)
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
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
                setTextColor(if (selected) Ui.BG else Ui.TEXT)
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                background = Ui.pressable(if (selected) Ui.ORANGE else Ui.CARD, dp(20).toFloat())
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    current = i
                    render()
                    listScroll?.scrollTo(0, 0)
                }
            }
            tabs.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, match).apply {
                rightMargin = dp(6)
            })
        }

        box.removeAllViews()
        val phrases = categories.getOrNull(current)?.phrases ?: mutableListOf()
        if (phrases.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "定型文がまだないよ。⚙ から追加してね"
                setTextColor(Ui.SUB)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(40), 0, 0)
            })
            return
        }

        // 2列で並べる
        phrases.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { p -> row.addView(phraseCell(p), cellParams()) }
            if (pair.size == 1) row.addView(View(this), cellParams())
            box.addView(row, LinearLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            })
        }
    }

    private fun cellParams() = LinearLayout.LayoutParams(0, dp(58), 1f).apply {
        leftMargin = dp(3)
        rightMargin = dp(3)
    }

    private fun phraseCell(p: Phrase): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = Ui.pressable(Ui.KEY, dp(10).toFloat())
        }
        cell.addView(TextView(this).apply {
            text = p.label()
            setTextColor(Ui.TEXT)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (p.title.isNotBlank()) {
            cell.addView(TextView(this).apply {
                text = p.body.replace("\n", " ")
                setTextColor(Ui.SUB)
                textSize = 11f
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

        // スペース
        row.addView(key("スペース", Ui.KEY).apply {
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                currentInputConnection?.commitText(" ", 1)
            }
        }, keyParams(2.2f))

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
        }, keyParams(1.3f))

        // ↵ 改行 / 送信
        row.addView(key("↵", Ui.ORANGE).apply {
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                enter()
            }
        }, keyParams(1.3f))

        return row
    }

    private fun key(label: String, color: Int): TextView = TextView(this).apply {
        text = label
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(if (color == Ui.ORANGE) Ui.BG else Ui.TEXT)
        background = Ui.pressable(color, dp(10).toFloat())
    }

    private fun keyParams(weight: Float) = LinearLayout.LayoutParams(0, match, weight).apply {
        leftMargin = dp(3)
        rightMargin = dp(3)
    }

    // ---------- 動作 ----------

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
