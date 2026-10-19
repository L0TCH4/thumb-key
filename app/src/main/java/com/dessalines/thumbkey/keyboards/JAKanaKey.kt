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
