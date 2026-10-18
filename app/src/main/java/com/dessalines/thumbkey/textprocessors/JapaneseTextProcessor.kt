package com.dessalines.thumbkey.textprocessors

import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dessalines.thumbkey.IMEService
import com.dessalines.thumbkey.utils.TAG

/** What the candidate bar shows. Replaced as a whole on every change so Compose sees it. */
class JapaneseBarState(
    /** True once the reading has been converted and 文節 are being chosen. */
    val converting: Boolean,
    /** The text of every 文節 while converting, so the focused one can be marked. */
    val preview: List<String>,
    val focus: Int,
    val candidates: List<String>,
    /** Index of the chosen candidate in [candidates], or -1 while still typing. */
    val selected: Int,
    /** Bumped on every update; lets the bar scroll back to the start for a new list. */
    val version: Int,
) {
    companion object {
        val EMPTY = JapaneseBarState(false, emptyList(), 0, emptyList(), -1, 0)
    }
}

/**
 * Japanese input with kana-kanji conversion.
 *
 * Typed hiragana are held in Android's composing text (underlined). Space converts the reading,
 * splitting it into 文節; space again cycles the focused 文節's candidates. The candidate bar (see
 * JapaneseCandidateBar) shows the candidates and moves between and resizes 文節. Enter, or typing
 * more kana, confirms; backspace steps back to the reading.
 *
 * Anything that is not hiragana (katakana, punctuation, digits, ...) ends the composition and is
 * committed as typed, so the katakana layouts pass straight through.
 *
 * The dakuten key sends "゛" through here so it can edit the reading instead of committed text.
 */
class JapaneseTextProcessor : TextProcessor {
    private val composition = JapaneseComposition(JapaneseConverter(JapaneseDictionary))

    /** Observed by the candidate bar. */
    var bar by mutableStateOf(JapaneseBarState.EMPTY)
        private set

    private var barVersion = 0

    /** Text currently held in the composing region. */
    private var display = ""

    /** Cursor offset at which the composing region starts. */
    private var base = 0

    /** Longest the composing text has been; late cursor updates may still describe an older, longer state. */
    private var span = 0
    private var trackCursor = true

    fun warmUp(ime: IMEService) = JapaneseDictionary.warmUp(ime)

    // ---- keyboard input ---------------------------------------------------------------------

    override fun handleCommitText(
        ime: IMEService,
        input: CharSequence,
    ) {
        val ic = ime.currentInputConnection ?: return
        JapaneseDictionary.warmUp(ime)
        dropIfStale(ic)
        val text = input.toString()

        when {
            text == DAKUTEN -> applyDakuten(ic)

            text == " " && !composition.isEmpty -> {
                JapaneseDictionary.awaitLoaded()
                composition.convertOrNext()
                render(ic)
            }

            text.length == 1 && isHiragana(text[0]) -> {
                if (composition.converting) commit(ic)
                if (composition.isEmpty) syncBase(ime)
                composition.append(text[0])
                render(ic)
            }

            else -> {
                if (!composition.isEmpty) commit(ic)
                ic.commitText(text, 1)
            }
        }
    }

    override fun handleKeyEvent(
        ime: IMEService,
        ev: KeyEvent,
    ) {
        val ic = ime.currentInputConnection ?: return
        dropIfStale(ic)

        if (composition.isEmpty) {
            ic.sendKeyEvent(ev)
            return
        }

        when (ev.keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                if (composition.converting) {
                    // Back out of the conversion, keep the reading.
                    composition.cancelConversion()
                    render(ic)
                } else {
                    composition.deleteLast()
                    if (composition.isEmpty) {
                        ic.setComposingText("", 1)
                        ic.finishComposingText()
                        reset()
                    } else {
                        render(ic)
                    }
                }
            }

            // Enter only confirms while something is being composed.
            KeyEvent.KEYCODE_ENTER -> commit(ic)

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            -> {
                if (composition.converting) {
                    if (ev.action == KeyEvent.ACTION_DOWN) {
                        when (ev.keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> composition.moveFocus(-1)
                            KeyEvent.KEYCODE_DPAD_RIGHT -> composition.moveFocus(1)
                            KeyEvent.KEYCODE_DPAD_UP -> composition.step(-1)
                            else -> composition.step(1)
                        }
                        render(ic)
                    }
                } else {
                    commit(ic)
                    ic.sendKeyEvent(ev)
                }
            }

            else -> {
                commit(ic)
                ic.sendKeyEvent(ev)
            }
        }
    }

    override fun handleFinishInput(ime: IMEService) {
        if (!composition.isEmpty) commit(ime.currentInputConnection)
    }

    override fun handleCursorUpdate(
        ime: IMEService,
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
    ) {
        if (composition.isEmpty || !trackCursor) return

        // Cursor updates arrive asynchronously, so one may describe an earlier state of the
        // composing text. Anything inside the region (or at its ends) is ours; only leaving it
        // counts as the user moving the caret.
        if (newSelStart == newSelEnd && newSelStart in base..(base + span)) return

        Log.d(TAG, "Japanese: cursor moved to $newSelStart, committing")
        commit(ime.currentInputConnection)
    }

    override fun updateCursorPosition(ime: IMEService) {
        if (composition.isEmpty) syncBase(ime)
    }

    // ---- candidate bar actions --------------------------------------------------------------

    /** A candidate was tapped: choose it for the focused 文節, or commit it when still typing. */
    fun onCandidateTapped(
        ime: IMEService,
        index: Int,
    ) {
        val ic = ime.currentInputConnection ?: return
        dropIfStale(ic)
        if (composition.isEmpty) return

        if (composition.converting) {
            composition.select(index)
            if (composition.atLastSegment) {
                commit(ic)
            } else {
                composition.moveFocus(1)
                render(ic)
            }
        } else {
            val text = composition.suggestions().getOrNull(index) ?: return
            ic.commitText(text, 1)
            reset()
        }
    }

    fun onMoveFocus(
        ime: IMEService,
        delta: Int,
    ) {
        val ic = ime.currentInputConnection ?: return
        composition.moveFocus(delta)
        render(ic)
    }

    fun onResize(
        ime: IMEService,
        delta: Int,
    ) {
        val ic = ime.currentInputConnection ?: return
        composition.resize(delta)
        render(ic)
    }

    // ---- internals --------------------------------------------------------------------------

    private fun applyDakuten(ic: InputConnection) {
        if (composition.converting) return

        if (!composition.isEmpty) {
            val last = composition.lastChar() ?: return
            val next = DAKUTEN_NEXT[last] ?: return
            composition.replaceLast(next)
            render(ic)
            return
        }

        // Nothing being composed: edit the character already in the field, like the other layouts.
        val before = ic.getTextBeforeCursor(1, 0)?.firstOrNull() ?: return
        val next = DAKUTEN_NEXT[before] ?: return
        ic.deleteSurroundingText(1, 0)
        ic.commitText(next.toString(), 1)
    }

    /** Shows the composition in the field and refreshes the bar. */
    private fun render(ic: InputConnection) {
        if (composition.isEmpty) {
            reset()
            return
        }
        display = composition.display
        if (display.length > span) span = display.length
        ic.setComposingText(display, 1)
        publishBar()
    }

    private fun publishBar() {
        bar =
            if (composition.isEmpty) {
                JapaneseBarState.EMPTY
            } else {
                JapaneseBarState(
                    converting = composition.converting,
                    preview = if (composition.converting) composition.segments.map { it.text } else emptyList(),
                    focus = composition.focus,
                    candidates = composition.barCandidates(),
                    selected = if (composition.converting) composition.segments[composition.focus].selected else -1,
                    version = ++barVersion,
                )
            }
    }

    /** Keeps whatever is shown and ends the composition. */
    private fun commit(ic: InputConnection?) {
        ic?.finishComposingText()
        reset()
    }

    private fun reset() {
        composition.clear()
        display = ""
        span = 0
        bar = JapaneseBarState.EMPTY
    }

    /**
     * If the field no longer holds our composing text (an app cleared it after Send, say), forget
     * the composition instead of writing the old text back.
     */
    private fun dropIfStale(ic: InputConnection) {
        if (composition.isEmpty) return
        val before = ic.getTextBeforeCursor(display.length, 0)?.toString()
        if (before != display) {
            Log.d(TAG, "Japanese: field changed under the composition, dropping it")
            reset()
        }
    }

    private fun syncBase(ime: IMEService) {
        val extracted = ime.currentInputConnection?.getExtractedText(ExtractedTextRequest(), 0)
        if (extracted == null) {
            // Editor can't report the caret; stop second-guessing cursor updates.
            trackCursor = false
        } else {
            trackCursor = true
            base = extracted.selectionStart
        }
    }

    private fun isHiragana(c: Char): Boolean = c in 'ぁ'..'ゖ' || c == 'ー'

    companion object {
        private const val DAKUTEN = "゛"

        // Same steps as the dakuten key in the layouts before this processor
        // (か → が → ゕ, つ → っ → づ, は → ば → ぱ, ...).
        private val DAKUTEN_FORWARD: Map<Char, Char> =
            mapOf(
                'あ' to 'ぁ',
                'い' to 'ぃ',
                'う' to 'ぅ',
                'え' to 'ぇ',
                'お' to 'ぉ',
                'ぅ' to 'ゔ',
                'か' to 'が',
                'き' to 'ぎ',
                'く' to 'ぐ',
                'け' to 'げ',
                'こ' to 'ご',
                'が' to 'ゕ',
                'げ' to 'ゖ',
                'さ' to 'ざ',
                'し' to 'じ',
                'す' to 'ず',
                'せ' to 'ぜ',
                'そ' to 'ぞ',
                'た' to 'だ',
                'ち' to 'ぢ',
                'つ' to 'っ',
                'て' to 'で',
                'と' to 'ど',
                'っ' to 'づ',
                'は' to 'ば',
                'ひ' to 'び',
                'ふ' to 'ぶ',
                'へ' to 'べ',
                'ほ' to 'ぼ',
                'ば' to 'ぱ',
                'び' to 'ぴ',
                'ぶ' to 'ぷ',
                'べ' to 'ぺ',
                'ぼ' to 'ぽ',
                'や' to 'ゃ',
                'ゆ' to 'ゅ',
                'よ' to 'ょ',
                'わ' to 'ゎ',
                'ゝ' to 'ゞ',
                'ア' to 'ァ',
                'イ' to 'ィ',
                'ウ' to 'ゥ',
                'エ' to 'ェ',
                'オ' to 'ォ',
                'ゥ' to 'ヴ',
                'カ' to 'ガ',
                'キ' to 'ギ',
                'ク' to 'グ',
                'ケ' to 'ゲ',
                'コ' to 'ゴ',
                'ガ' to 'ヵ',
                'ゲ' to 'ヶ',
                'サ' to 'ザ',
                'シ' to 'ジ',
                'ス' to 'ズ',
                'セ' to 'ゼ',
                'ソ' to 'ゾ',
                'タ' to 'ダ',
                'チ' to 'ヂ',
                'ツ' to 'ッ',
                'テ' to 'デ',
                'ト' to 'ド',
                'ッ' to 'ヅ',
                'ハ' to 'バ',
                'ヒ' to 'ビ',
                'フ' to 'ブ',
                'ヘ' to 'ベ',
                'ホ' to 'ボ',
                'バ' to 'パ',
                'ビ' to 'ピ',
                'ブ' to 'プ',
                'ベ' to 'ペ',
                'ボ' to 'ポ',
                'ヤ' to 'ャ',
                'ユ' to 'ュ',
                'ヨ' to 'ョ',
                'ワ' to 'ヷ',
                'ヰ' to 'ヸ',
                'ヱ' to 'ヹ',
                'ヲ' to 'ヺ',
                'ヷ' to 'ヮ',
                'ヽ' to 'ヾ',
            )

        // The last form of each chain wraps back to its first (ぱ → は, づ → つ, ...).
        private val DAKUTEN_NEXT: Map<Char, Char> =
            DAKUTEN_FORWARD +
                (DAKUTEN_FORWARD.keys - DAKUTEN_FORWARD.values.toSet()).associate { head ->
                    var c = head
                    while (DAKUTEN_FORWARD.containsKey(c)) c = DAKUTEN_FORWARD.getValue(c)
                    c to head
                }
    }
}

/** One processor shared by every Japanese layout, so a composition survives switching between them. */
val JAPANESE_TEXT_PROCESSOR = JapaneseTextProcessor()
