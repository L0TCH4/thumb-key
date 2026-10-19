package com.dessalines.thumbkey.textprocessors

/**
 * The text being composed: first a hiragana reading that is typed and edited, then (after the user
 * asks for conversion) a list of 文節 each showing one of its candidates, with one of them focused.
 *
 * Pure logic with no Android types, so it can be tested on its own.
 */
class JapaneseComposition(
    private val converter: JapaneseConverter,
) {
    class Segment(
        val reading: String,
        val candidates: List<String>,
        var selected: Int = 0,
    ) {
        val text: String get() = candidates[selected]
    }

    private val reading = StringBuilder()
    private val segmentList = ArrayList<Segment>()

    /** Index of the 文節 that candidate choices and resizing apply to. */
    var focus = 0
        private set

    /** False while the reading is being typed, true once it has been converted. */
    var converting = false
        private set

    /** SKK style: the whole reading is one word, and an okurigana kana may follow it. */
    var skk = false

    /** SKK okurigana kana typed after the reading, waiting for conversion. */
    var okuri: Char? = null
        private set

    private var suggestionsFor = ""
    private var suggestionsCache: List<String> = emptyList()

    val isEmpty: Boolean get() = reading.isEmpty()
    val readingText: String get() = reading.toString()
    val segments: List<Segment> get() = segmentList

    /** What the text field should currently show. */
    val display: String
        get() = if (converting) segmentList.joinToString("") { it.text } else reading.toString() + (okuri ?: "")

    fun append(c: Char) {
        cancelConversion()
        okuri?.let { reading.append(it) }
        okuri = null
        reading.append(c)
    }

    /** Removes the last typed character; returns false if there was nothing to remove. */
    fun deleteLast(): Boolean {
        cancelConversion()
        if (okuri != null) {
            okuri = null
            return true
        }
        if (reading.isEmpty()) return false
        reading.deleteCharAt(reading.length - 1)
        return true
    }

    fun lastChar(): Char? = okuri ?: reading.lastOrNull()

    /** SKK: the next kana is the okurigana; it waits here until the user converts. */
    fun setOkuri(c: Char) {
        cancelConversion()
        okuri = c
    }

    fun replaceLast(c: Char) {
        cancelConversion()
        if (okuri != null) {
            okuri = c
        } else if (reading.isNotEmpty()) reading.setCharAt(reading.length - 1, c)
    }

    /** Converts the reading, or if already converting, moves to the next candidate of the focused 文節. */
    fun convertOrNext() {
        if (reading.isEmpty()) return
        if (skk) {
            convertSkk()
            return
        }
        if (!converting) {
            segmentList.clear()
            for (b in converter.convert(reading.toString())) segmentList.add(Segment(b.reading, b.candidates))
            focus = 0
            converting = true
        } else {
            step(1)
        }
    }

    /** SKK conversion: the whole reading (plus okurigana, if any) is one word. */
    fun convertSkk(): Boolean {
        if (reading.isEmpty()) return false
        if (converting) {
            step(1)
            return true
        }
        val cands = skkCandidates()
        if (cands.isEmpty()) return false
        segmentList.clear()
        segmentList.add(Segment(reading.toString() + (okuri ?: ""), cands))
        focus = 0
        converting = true
        return true
    }

    private fun skkCandidates(): List<String> {
        val r = reading.toString()
        val ok = okuri ?: return converter.skkWord(r)
        val withOkuri = converter.skkOkuri(r, ok)
        return if (withOkuri.isNotEmpty()) withOkuri else converter.skkWord(r + ok)
    }

    /** Cycles the focused 文節's candidate by [delta] (wraps around). */
    fun step(delta: Int) {
        if (!converting) return
        val seg = segmentList[focus]
        val n = seg.candidates.size
        seg.selected = ((seg.selected + delta) % n + n) % n
    }

    fun select(index: Int) {
        if (!converting) return
        val seg = segmentList[focus]
        if (index in seg.candidates.indices) seg.selected = index
    }

    fun moveFocus(delta: Int) {
        if (!converting) return
        focus = (focus + delta).coerceIn(0, segmentList.size - 1)
    }

    val atLastSegment: Boolean get() = !converting || focus == segmentList.size - 1

    /**
     * Makes the focused 文節 [delta] characters longer (or shorter), taking from or giving to what
     * follows. Everything after it is converted again.
     */
    fun resize(delta: Int) {
        if (!converting) return
        val seg = segmentList[focus]
        val rest = StringBuilder()
        for (i in focus + 1 until segmentList.size) rest.append(segmentList[i].reading)
        val all = seg.reading + rest
        val newLen = seg.reading.length + delta
        if (newLen < 1 || newLen > all.length) return

        val head = all.substring(0, newLen)
        val remainder = all.substring(newLen)
        while (segmentList.size > focus) segmentList.removeAt(segmentList.size - 1)
        segmentList.add(Segment(head, converter.candidatesFor(head)))
        for (b in converter.convert(remainder)) segmentList.add(Segment(b.reading, b.candidates))
    }

    /** Goes back to the plain reading, keeping it. */
    fun cancelConversion() {
        if (!converting) return
        converting = false
        segmentList.clear()
        focus = 0
    }

    fun clear() {
        reading.clear()
        okuri = null
        segmentList.clear()
        focus = 0
        converting = false
    }

    /** Whole-reading candidates offered while typing. The first is the best conversion of everything. */
    fun suggestions(): List<String> {
        val r = reading.toString() + (okuri ?: "")
        if (r.isEmpty()) return emptyList()
        if (r != suggestionsFor) {
            suggestionsFor = r
            suggestionsCache = if (skk) skkCandidates() else converter.candidatesFor(r)
        }
        return suggestionsCache
    }

    /** Candidates the bar should list: suggestions while typing, the focused 文節's once converting. */
    fun barCandidates(): List<String> = if (converting) segmentList[focus].candidates else suggestions()
}
