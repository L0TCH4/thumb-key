package com.dessalines.thumbkey.textprocessors

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import com.dessalines.thumbkey.IMEService
import com.dessalines.thumbkey.utils.TAG
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Kana-kanji dictionary backed by assets/ja_dict.tsv (derived from SKK-JISYO.L, GPL-2.0-or-later).
 *
 * The file is `reading<TAB>candidate<TAB>candidate...` per line, sorted by the UTF-8 bytes of the
 * reading, so lookups are a binary search over the raw bytes. No per-entry objects are created.
 */
object JapaneseDictionary {
    private const val ASSET = "ja_dict.tsv"
    private const val NEWLINE = '\n'.code.toByte()
    private const val TAB = '\t'.code.toByte()

    @Volatile private var data: ByteArray? = null

    @Volatile private var offsets: IntArray? = null

    @Volatile private var started = false
    private val ready = CountDownLatch(1)

    /** Starts loading the dictionary on a background thread. Safe to call repeatedly. */
    @Synchronized
    fun warmUp(context: Context) {
        if (started) return
        started = true
        val appContext = context.applicationContext
        Thread {
            try {
                val bytes = appContext.assets.open(ASSET).use { it.readBytes() }
                var count = 0
                for (b in bytes) if (b == NEWLINE) count++
                val lineStarts = IntArray(count)
                var line = 0
                var start = 0
                for (i in bytes.indices) {
                    if (bytes[i] == NEWLINE) {
                        lineStarts[line++] = start
                        start = i + 1
                    }
                }
                data = bytes
                offsets = lineStarts
                Log.d(TAG, "Japanese dictionary loaded: $count entries")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load Japanese dictionary", e)
            } finally {
                ready.countDown()
            }
        }.start()
    }

    /** Returns the candidates for an exact hiragana reading, best first. Empty if none. */
    fun lookup(reading: String): List<String> {
        // Normally already loaded by now; only waits if the user converts right after first typing.
        ready.await(2, TimeUnit.SECONDS)
        val bytes = data ?: return emptyList()
        val lineStarts = offsets ?: return emptyList()
        val key = reading.toByteArray(Charsets.UTF_8)

        var lo = 0
        var hi = lineStarts.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val cmp = compareKey(bytes, lineStarts[mid], key)
            if (cmp < 0) {
                lo = mid + 1
            } else if (cmp > 0) {
                hi = mid - 1
            } else {
                return readCandidates(bytes, lineStarts[mid])
            }
        }
        return emptyList()
    }

    /** Compares the key of the line starting at [start] with [key]; negative if the line sorts first. */
    private fun compareKey(
        bytes: ByteArray,
        start: Int,
        key: ByteArray,
    ): Int {
        var i = 0
        while (true) {
            val lineByte = bytes[start + i]
            val lineKeyEnded = lineByte == TAB
            if (i == key.size) return if (lineKeyEnded) 0 else 1
            if (lineKeyEnded) return -1
            val a = lineByte.toInt() and 0xFF
            val b = key[i].toInt() and 0xFF
            if (a != b) return a - b
            i++
        }
    }

    private fun readCandidates(
        bytes: ByteArray,
        start: Int,
    ): List<String> {
        var end = start
        while (bytes[end] != NEWLINE) end++
        return String(bytes, start, end - start, Charsets.UTF_8).split('\t').drop(1)
    }
}

/**
 * Hiragana input with kana-kanji conversion.
 *
 * Typed kana are held in Android's composing text (shown underlined). Space converts the reading
 * to the first dictionary candidate and cycles through the rest on every further press (the last
 * candidates are the plain hiragana and katakana). Enter or typing more kana confirms the current
 * candidate; backspace steps back from a candidate to the reading, then deletes reading characters.
 *
 * The dakuten key is routed through here as a "゛" commit (see the layout) so that it can edit the
 * reading instead of text that is already committed.
 */
class JapaneseTextProcessor : TextProcessor {
    private val reading = StringBuilder()
    private var candidates: List<String> = emptyList()

    /** -1 while the reading itself is shown, otherwise the index of the shown candidate. */
    private var index = -1

    /** Text currently held in the composing region. */
    private var display = ""

    /** Cursor offset at which the composing region starts. */
    private var base = 0
    private var trackCursor = true

    private val composing: Boolean
        get() = reading.isNotEmpty()

    override fun handleCommitText(
        ime: IMEService,
        input: CharSequence,
    ) {
        val ic = ime.currentInputConnection ?: return
        JapaneseDictionary.warmUp(ime)
        val text = input.toString()

        when {
            text == DAKUTEN -> applyDakuten(ic)

            text == " " && composing -> convert(ic)

            text.length == 1 && isKana(text[0]) -> {
                if (index >= 0) commit(ic)
                if (!composing) syncBase(ime)
                reading.append(text)
                show(ic, reading.toString())
            }

            else -> {
                if (composing) commit(ic)
                ic.commitText(text, 1)
            }
        }
    }

    override fun handleKeyEvent(
        ime: IMEService,
        ev: KeyEvent,
    ) {
        val ic = ime.currentInputConnection ?: return

        if (!composing) {
            ic.sendKeyEvent(ev)
            return
        }

        when (ev.keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                if (index >= 0) {
                    // Back out of the conversion, keep the reading.
                    index = -1
                    show(ic, reading.toString())
                } else {
                    reading.deleteCharAt(reading.length - 1)
                    if (reading.isEmpty()) {
                        ic.setComposingText("", 1)
                        ic.finishComposingText()
                        reset()
                    } else {
                        show(ic, reading.toString())
                    }
                }
            }

            // Enter only confirms while something is being composed.
            KeyEvent.KEYCODE_ENTER -> commit(ic)

            else -> {
                commit(ic)
                ic.sendKeyEvent(ev)
            }
        }
    }

    override fun handleFinishInput(ime: IMEService) {
        if (composing) commit(ime.currentInputConnection)
    }

    override fun handleCursorUpdate(
        ime: IMEService,
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
    ) {
        if (!composing || !trackCursor) return

        // Cursor updates arrive asynchronously, so one may describe an earlier, shorter state of the
        // composing text. Anything inside the region (or at its ends) is ours; only leaving it
        // counts as the user moving the caret.
        if (newSelStart == newSelEnd && newSelStart in base..(base + display.length)) return

        Log.d(TAG, "Japanese: cursor moved to $newSelStart, committing")
        commit(ime.currentInputConnection)
    }

    override fun updateCursorPosition(ime: IMEService) {
        if (!composing) syncBase(ime)
    }

    private fun applyDakuten(ic: InputConnection) {
        // No dakuten on a candidate; step back to the reading first.
        if (index >= 0) return

        if (composing) {
            val next = DAKUTEN_NEXT[reading.last()] ?: return
            reading.setCharAt(reading.length - 1, next)
            show(ic, reading.toString())
            return
        }

        // Nothing being composed: edit the character already in the field, like the other layouts.
        val before = ic.getTextBeforeCursor(1, 0)?.firstOrNull() ?: return
        val next = DAKUTEN_NEXT[before] ?: return
        ic.deleteSurroundingText(1, 0)
        ic.commitText(next.toString(), 1)
    }

    private fun convert(ic: InputConnection) {
        if (index < 0) {
            val r = reading.toString()
            candidates = (JapaneseDictionary.lookup(r) + listOf(r, toKatakana(r))).distinct()
            index = 0
        } else {
            index = (index + 1) % candidates.size
        }
        show(ic, candidates[index])
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

    private fun show(
        ic: InputConnection,
        text: String,
    ) {
        display = text
        ic.setComposingText(text, 1)
    }

    /** Keeps whatever is shown and ends the composition. */
    private fun commit(ic: InputConnection?) {
        ic?.finishComposingText()
        reset()
    }

    private fun reset() {
        reading.clear()
        candidates = emptyList()
        index = -1
        display = ""
    }

    private fun isKana(c: Char): Boolean = c in 'ぁ'..'ゖ' || c == 'ー'

    private fun toKatakana(s: String): String =
        s
            .map { if (it in 'ぁ'..'ゖ') (it.code + 0x60).toChar() else it }
            .joinToString("")

    companion object {
        private const val DAKUTEN = "゛"

        // Same steps as the dakuten key in the other layouts (か → が → ゕ, つ → っ → づ, ...).
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
