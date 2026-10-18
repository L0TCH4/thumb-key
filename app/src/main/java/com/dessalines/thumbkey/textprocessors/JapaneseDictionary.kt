package com.dessalines.thumbkey.textprocessors

import android.content.Context
import android.util.Log
import com.dessalines.thumbkey.utils.TAG
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The bundled dictionaries (derived from SKK-JISYO.L, GPL-2.0-or-later; see ja_dict_NOTICE.txt),
 * loaded once on a background thread.
 *
 * Lookups never block: until loading finishes they simply find nothing. Call [awaitLoaded] before
 * a conversion the user asked for.
 */
object JapaneseDictionary : JapaneseLexicon {
    private const val WORDS_ASSET = "ja_dict.tsv"
    private const val STEMS_ASSET = "ja_dict_okuri.tsv"

    @Volatile private var wordTable: SortedTsv? = null

    @Volatile private var stemTable: SortedTsv? = null

    @Volatile private var started = false
    private val ready = CountDownLatch(1)

    /** Starts loading on a background thread. Safe to call repeatedly. */
    @Synchronized
    fun warmUp(context: Context) {
        if (started) return
        started = true
        val appContext = context.applicationContext
        Thread {
            try {
                val words = appContext.assets.open(WORDS_ASSET).use { it.readBytes() }
                val stems = appContext.assets.open(STEMS_ASSET).use { it.readBytes() }
                wordTable = SortedTsv(words)
                stemTable = SortedTsv(stems)
                Log.d(TAG, "Japanese dictionary loaded: ${wordTable?.size} words, ${stemTable?.size} stems")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load Japanese dictionary", e)
            } finally {
                ready.countDown()
            }
        }.start()
    }

    /** Waits briefly for loading to finish (it normally already has). */
    fun awaitLoaded() {
        ready.await(3, TimeUnit.SECONDS)
    }

    override fun words(reading: String): LexEntry? = entry(wordTable?.lookup(reading))

    override fun stems(key: String): LexEntry? = entry(stemTable?.lookup(key))

    /** Stored values are `*` (common) or `-` (rare) followed by the candidates. */
    private fun entry(values: List<String>?): LexEntry? {
        if (values == null || values.size < 2) return null
        return LexEntry(values[0] == "*", values.subList(1, values.size))
    }
}
