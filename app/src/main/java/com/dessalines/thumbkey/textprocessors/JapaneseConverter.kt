package com.dessalines.thumbkey.textprocessors

/** A dictionary hit. [common] is true for everyday words, false for ones only the large dictionary has. */
class LexEntry(
    val common: Boolean,
    val candidates: List<String>,
)

/** Read-only view of the dictionaries. */
interface JapaneseLexicon {
    /** Candidates for an exact hiragana reading (nouns and the like), best first. */
    fun words(reading: String): LexEntry?

    /**
     * Candidates for an inflecting word's stem, keyed SKK-style: the stem's kana followed by the
     * first letter of the okurigana's consonant, e.g. "たb" for 食べ(る).
     */
    fun stems(key: String): LexEntry?
}

/** One 文節: the reading it covers and its candidates, best first. */
class Bunsetsu(
    val reading: String,
    val candidates: List<String>,
)

/**
 * Splits a hiragana reading into 文節 and offers candidates for each.
 *
 * There is no frequency or grammar data behind this, so it is a plain lowest-cost path search:
 * dictionary words and inflecting stems are cheaper the longer they are (and cheaper again when they
 * are everyday words), particles and auxiliary endings are cheap when they follow a word, and
 * anything left over stays as hiragana.
 */
class JapaneseConverter(
    private val lexicon: JapaneseLexicon,
) {
    private enum class Kind { WORD, FUNC, UNKNOWN }

    private class Edge(
        val from: Int,
        val to: Int,
        val kind: Kind,
        val heads: List<String>,
        val cost: Int,
        val freeStart: Boolean = false,
        /** An inflecting stem that is not a complete word (渡し, 食べ): an ending must follow. */
        val needsAux: Boolean = false,
        /** A bare particle, which cannot follow such a stem. */
        val particle: Boolean = false,
        /** A verb ending (ます, た, て, ない ...) that fits right after such a stem. */
        val strongAux: Boolean = false,
        /** An ending that only verbs take, so it is unlikely straight after a noun. */
        val verbOnly: Boolean = false,
    )

    /** A 文節 being assembled: an optional head, then any particles that follow it. */
    private class Group(
        val start: Int,
        var end: Int,
        val head: Edge?,
        val tailStart: Int,
        val unknownRun: Boolean = false,
    ) {
        var hasTail = false
    }

    /** Splits [reading] into 文節 using the best-scoring path. */
    fun convert(reading: String): List<Bunsetsu> {
        if (reading.isEmpty()) return emptyList()
        val path = bestPath(reading)

        val groups = ArrayList<Group>()
        for (e in path) {
            val last = groups.lastOrNull()
            when (e.kind) {
                Kind.WORD -> groups.add(Group(e.from, e.to, e, e.to))

                Kind.UNKNOWN -> {
                    // Consecutive leftover kana form one run; a particle after it would end the run.
                    if (last != null && last.unknownRun && !last.hasTail) {
                        last.end = e.to
                    } else {
                        groups.add(Group(e.from, e.to, null, e.to, unknownRun = true))
                    }
                }

                Kind.FUNC -> {
                    if (last == null) {
                        groups.add(Group(e.from, e.to, null, e.from).also { it.hasTail = true })
                    } else {
                        last.end = e.to
                        last.hasTail = true
                    }
                }
            }
        }

        return groups.map { g ->
            val whole = reading.substring(g.start, g.end)
            Bunsetsu(whole, candidatesOf(g, reading))
        }
    }

    /**
     * Candidates for [reading] treated as one unit (used when the user resizes a 文節). When the
     * text would normally split, the best conversion of the whole thing comes first.
     */
    fun candidatesFor(reading: String): List<String> {
        if (reading.isEmpty()) return emptyList()
        val parts = convert(reading)
        if (parts.size == 1) return parts[0].candidates
        val joined = parts.joinToString("") { it.candidates[0] }
        val whole = lexicon.words(reading)?.candidates ?: emptyList()
        return (listOf(joined) + whole + listOf(reading, toKatakana(reading))).distinct()
    }

    private fun candidatesOf(
        g: Group,
        reading: String,
    ): List<String> {
        val whole = reading.substring(g.start, g.end)
        val out = ArrayList<String>()
        val head = g.head
        if (head != null) {
            val tail = reading.substring(g.tailStart, g.end)
            for (h in head.heads) out.add(h + tail)
            // A word that happens to cover the whole 文節 (そして, ところで, ...).
            if (tail.isNotEmpty()) lexicon.words(whole)?.let { out.addAll(it.candidates) }
        }
        out.add(whole)
        out.add(toKatakana(whole))
        return out.distinct()
    }

    // ---- search ------------------------------------------------------------------------------

    private fun bestPath(reading: String): List<Edge> {
        val n = reading.length
        val edges = Array(n) { i -> edgesAt(reading, i) }

        // State = what the previous edge was: 0 start, 1 word, 2 function word, 3 unknown,
        // 4 inflecting stem still waiting for its ending.
        val inf = Int.MAX_VALUE / 2
        val best = Array(n + 1) { IntArray(STATES) { inf } }
        val backEdge = Array(n + 1) { arrayOfNulls<Edge>(STATES) }
        val backState = Array(n + 1) { IntArray(STATES) }
        best[0][0] = 0

        for (i in 0 until n) {
            for (s in 0 until STATES) {
                val base = best[i][s]
                if (base >= inf) continue
                for (e in edges[i]) {
                    var cost = base + e.cost
                    if (e.kind == Kind.FUNC && !e.freeStart && (s == 0 || s == 3)) cost += NO_HEAD_PENALTY
                    if (s == 4) {
                        if (!(e.kind == Kind.FUNC && !e.particle)) cost += NEEDS_ENDING_PENALTY
                        if (e.strongAux) cost -= AUX_REWARD
                    }
                    if (s == 1 && e.verbOnly) cost += VERB_ENDING_PENALTY
                    // Two particles in a row are rare; real compounds are listed as one entry.
                    if (s == 2 && e.kind == Kind.FUNC && !e.freeStart) cost += FUNC_CHAIN_PENALTY
                    val ns =
                        when (e.kind) {
                            Kind.WORD -> if (e.needsAux) 4 else 1
                            Kind.FUNC -> 2
                            Kind.UNKNOWN -> 3
                        }
                    if (cost < best[e.to][ns]) {
                        best[e.to][ns] = cost
                        backEdge[e.to][ns] = e
                        backState[e.to][ns] = s
                    }
                }
            }
        }

        var state = (0 until STATES).minByOrNull { best[n][it] + if (it == 4) NEEDS_ENDING_PENALTY else 0 } ?: 0
        val path = ArrayList<Edge>()
        var pos = n
        while (pos > 0) {
            val e = backEdge[pos][state] ?: break
            path.add(e)
            val prev = backState[pos][state]
            pos = e.from
            state = prev
        }
        path.reverse()
        return path
    }

    private fun edgesAt(
        reading: String,
        i: Int,
    ): List<Edge> {
        val n = reading.length
        val out = ArrayList<Edge>()

        // Plain dictionary words.
        for (j in (i + 1)..minOf(n, i + MAX_WORD)) {
            val found = lexicon.words(reading.substring(i, j)) ?: continue
            out.add(Edge(i, j, Kind.WORD, found.candidates, wordCost(j - i, found.common)))
        }

        // Inflecting words: stem + first okurigana kana, e.g. た + べ -> 食べ.
        for (k in (i + 1)..minOf(n - 1, i + MAX_STEM)) {
            val x = reading[k]
            val letters = OKURI_LETTERS[x] ?: continue
            val stem = reading.substring(i, k)
            val found = LinkedHashSet<String>()
            var common = false
            for (letter in letters) {
                val entry = lexicon.stems(stem + letter) ?: continue
                if (entry.common) common = true
                for (c in entry.candidates) found.add(c + x)
            }
            if (found.isNotEmpty()) {
                // 買う, 食べる ... are words on their own; 渡し, 食べ need an ending, and lose ties to nouns.
                val complete = x in COMPLETE_ENDINGS
                val base = wordCost(k + 1 - i, common)
                if (complete) out.add(Edge(i, k + 1, Kind.WORD, found.toList(), base - INFLECTING_BONUS))
                // 空い・書い can be a stem before た/て as well as a word of their own.
                if (!complete || x == 'い') {
                    out.add(Edge(i, k + 1, Kind.WORD, found.toList(), base + NEEDS_AUX_PENALTY, needsAux = true))
                }
            }
        }

        // Particles and auxiliary endings, kept as hiragana.
        for (f in FUNCTION_WORDS) {
            if (reading.startsWith(f, i)) {
                out.add(
                    Edge(
                        i, i + f.length, Kind.FUNC, emptyList(), funcCost(f.length),
                        particle = f in PARTICLES, strongAux = f in STRONG_AUX, verbOnly = f in VERB_ONLY,
                    ),
                )
            }
        }
        for (f in KANA_WORDS) {
            if (reading.startsWith(f, i)) {
                out.add(Edge(i, i + f.length, Kind.FUNC, emptyList(), kanaWordCost(f.length), freeStart = true))
            }
        }

        // Anything else stays as typed.
        out.add(Edge(i, i + 1, Kind.UNKNOWN, emptyList(), UNKNOWN_COST))
        return out
    }

    private fun wordCost(
        len: Int,
        common: Boolean,
    ): Int {
        val base = 40 - 4 * minOf(len, 6)
        return if (common) base else base + RARE_PENALTY + if (len == 1) 10 else 0
    }

    private fun funcCost(len: Int): Int = 12 + 2 * len

    private fun kanaWordCost(len: Int): Int = 10 + len

    companion object {
        private const val MAX_WORD = 12
        private const val MAX_STEM = 6
        private const val UNKNOWN_COST = 45
        private const val NO_HEAD_PENALTY = 50
        private const val RARE_PENALTY = 24
        private const val INFLECTING_BONUS = 1
        private const val NEEDS_AUX_PENALTY = 2
        private const val NEEDS_ENDING_PENALTY = 60
        private const val STATES = 5
        private const val AUX_REWARD = 4
        private const val FUNC_CHAIN_PENALTY = 8
        private const val VERB_ENDING_PENALTY = 40

        /** Endings that clearly belong to a verb stem. */
        private val STRONG_AUX: Set<String> =
            setOf(
                "ます", "ました", "ません", "ましょう", "ませ", "た", "て", "たい", "たかった", "たくない", "ない", "なかった",
                "なく", "なくて", "なければ", "たら", "れば", "ば", "ず", "ぬ", "よう", "う", "れる", "られる", "せる", "させる",
                "れた", "られた", "せた", "させた", "ている", "ていた", "ています", "ていました", "だ", "で",
            )

        /** Endings that only follow verbs (unlike で, だ which also follow nouns). */
        private val VERB_ONLY: Set<String> = STRONG_AUX - setOf("だ", "で") + setOf("る", "れ", "ら", "せ", "ん")

        /** Okurigana kana that can end an inflecting word as it stands (書く, 食べる, 高い). */
        private const val COMPLETE_ENDINGS = "うくぐすつぬぶむるい"

        /** Particles that cannot follow an inflecting stem; endings like て, た, ます can. */
        private val PARTICLES: Set<String> =
            setOf(
                "は", "が", "を", "へ", "と", "も", "の", "や", "か", "ね", "よ", "な", "さ", "ぞ", "ぜ", "わ",
                "から", "まで", "より", "ので", "のに", "けど", "けれど", "けれども", "でも", "しか", "だけ", "ばかり",
                "など", "なんて", "という", "って", "とか", "こと", "ほど", "くらい", "ぐらい",
                "には", "では", "とは", "への", "での", "との", "からは", "までは", "にも", "とも",
                "のが", "のを", "のは", "かな", "かも", "かもしれない", "でしょう", "だろう", "ですか", "ですよ", "ですね",
            )

        fun toKatakana(s: String): String =
            buildString {
                for (c in s) append(if (c in 'ぁ'..'ゖ') (c.code + 0x60).toChar() else c)
            }

        /** Letters SKK uses to key an okurigana kana, most likely first. */
        private val OKURI_LETTERS: Map<Char, List<String>> =
            buildMap {
                fun row(
                    kana: String,
                    vararg letters: String,
                ) {
                    for (c in kana) put(c, letters.toList())
                }
                row("あ", "a")
                row("い", "i")
                row("う", "u")
                row("え", "e")
                row("お", "o")
                row("かきくけこ", "k")
                row("がぎぐげご", "g")
                row("さしすせそ", "s")
                row("ざずぜぞ", "z")
                row("じ", "z", "j")
                row("たちつてと", "t")
                row("だぢづでど", "d")
                row("なにぬねの", "n")
                row("はひふへほ", "h")
                row("ばびぶべぼ", "b")
                row("ぱぴぷぺぽ", "p")
                row("まみむめも", "m")
                row("やゆよ", "y")
                row("らりるれろ", "r")
                row("わゐゑを", "w")
                row("ん", "n", "m", "b")
                row("っ", "t", "r", "k", "s")
            }

        /** Particles and auxiliary endings. They cost little after a word, a lot with no word before. */
        private val FUNCTION_WORDS: List<String> =
            listOf(
                // particles
                "は", "が", "を", "に", "へ", "で", "と", "も", "の", "や", "か", "ね", "よ", "な", "さ", "ぞ", "ぜ", "わ",
                "から", "まで", "より", "ので", "のに", "けど", "けれど", "けれども", "でも", "しか", "だけ", "ばかり",
                "など", "なんて", "という", "って", "とか", "こと", "ほど", "くらい", "ぐらい", "ながら", "つつ",
                "たり", "だり", "には", "では", "とは", "への", "での", "との", "からは", "までは", "にも", "とも",
                "のが", "のを", "のは", "かな", "かも", "かもしれない", "でしょう", "だろう", "ですか",
                "ですよ", "ですね", "ますか", "ますよ", "ますね",
                // auxiliary endings
                "です", "でした", "ます", "ました", "ません", "ましょう", "ませ", "だ", "だった",
                "ない", "なかった", "なく", "なくて", "なければ", "なけれ", "たい", "たかった", "たくない",
                "た", "て", "ば", "れば", "たら", "なら", "よう", "そう", "らしい", "れる", "られる", "せる", "させる",
                "ず", "ぬ", "ん", "る", "れ", "ら", "せ", "う", "れた", "られた", "せた", "させた",
                // adjective endings (美し + い)
                "い", "く", "かった", "くて", "くない", "ければ", "かろう",
                // て-form chains
                "ている", "ていた", "ています", "ていました", "ていない", "てい", "ておく", "てある", "てしまう",
                "てくる", "てみる", "てくれ", "てもらう", "てあげる", "ても", "ては",
                // ある / いる / なる as helpers
                "ある", "あった", "あります", "ありません", "ありました", "いる", "いた", "います", "いました",
                "いません", "なる", "なった", "なります", "なりました",
            ).distinct().sortedByDescending { it.length }

        /**
         * Words normally written in kana, plus する-family endings. Usable at the start of a reading
         * too, and cheap enough to beat the kanji spellings.
         */
        private val KANA_WORDS: List<String> =
            listOf(
                "いい", "いいえ", "し", "して", "した", "しない", "しなかった", "します", "しました", "しません", "しましょう", "する",
                "すれば", "しよう", "しろ", "できる", "できた", "できない", "できます", "できました", "できません",
                "さん", "ちゃん", "くん", "たち", "ども",
                "これ", "それ", "あれ", "どれ", "ここ", "そこ", "あそこ", "どこ", "この", "その", "あの", "どの",
                "そして", "しかし", "だから", "それで", "それから", "ところで", "または", "ただし",
                "ありがとう", "おはよう", "こんにちは", "こんばんは", "さようなら", "すみません", "ごめんなさい",
                "ください", "ございます", "ございました", "おねがいします",
            ).sortedByDescending { it.length }
    }
}
