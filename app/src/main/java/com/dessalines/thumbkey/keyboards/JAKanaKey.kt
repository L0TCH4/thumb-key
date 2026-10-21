@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.dessalines.thumbkey.keyboards

import com.dessalines.thumbkey.textprocessors.JapaneseTextProcessor
import com.dessalines.thumbkey.utils.*
import com.dessalines.thumbkey.utils.ColorVariant.*
import com.dessalines.thumbkey.utils.KeyAction.*
import com.dessalines.thumbkey.utils.FontSizeVariant.*

/**
 * The key that used to open the emoji view. In the Japanese layouts it switches between hiragana and
 * katakana (tap), or acts as the SKK shift key when the SKK option is on. A long press always
 * toggles katakana. Emoji moved to a diagonal swipe.
 */
val JA_KANA_KEYC =
    KeyC(
        display = KeyDisplay.TextDisplay("カナ"),
        action = CommitText(JapaneseTextProcessor.KANA_KEY),
        size = LARGE,
        color = SECONDARY,
    )

val JA_KANA_KEY_ITEM =
    specialActionKeyItem(center = JA_KANA_KEYC).copy(
        topRight = TOGGLE_EMOJI_MODE_TRUE_KEYC,
        longPress = CommitText(JapaneseTextProcessor.KANA_KEY_LONG),
    )

val JA_KANA_KEY_ITEM_ALT =
    textEditKeyItem(center = JA_KANA_KEYC).copy(
        left = TOGGLE_EMOJI_MODE_TRUE_KEYC,
        longPress = CommitText(JapaneseTextProcessor.KANA_KEY_LONG),
    )

// ---- SKK option: the kana key becomes the shift key, the numeric key becomes the katakana key ----

private val JA_SKK_SHIFT_KEYC = JA_KANA_KEYC.copy(display = KeyDisplay.TextDisplay("▽"))

private val JA_KATAKANA_KEYC =
    JA_KANA_KEYC.copy(action = CommitText(JapaneseTextProcessor.KANA_KEY_LONG))

private fun skkShiftKey(item: KeyItemC): KeyItemC = item.copy(center = JA_SKK_SHIFT_KEYC)

/** Katakana key; sliding it left opens the numbers. The language switch it displaces moves to the upper right. */
private fun skkKatakanaKey(item: KeyItemC): KeyItemC =
    item.copy(
        center = JA_KATAKANA_KEYC,
        left = item.center,
        topRight = item.left ?: item.topRight,
        longPress = null,
    )

/** The layout as it should look with the SKK option on. */
fun KeyboardC.withSkkKeys(): KeyboardC =
    KeyboardC(
        arr.map { row ->
            row.map { key ->
                when {
                    key === JA_KANA_KEY_ITEM || key === JA_KANA_KEY_ITEM_ALT -> skkShiftKey(key)
                    key === NUMERIC_KEY_ITEM || key === NUMERIC_KEY_ITEM_ALT -> skkKatakanaKey(key)
                    else -> key
                }
            }
        },
    )
