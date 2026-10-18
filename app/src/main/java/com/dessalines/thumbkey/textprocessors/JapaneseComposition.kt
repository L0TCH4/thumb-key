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

    private var suggestionsFor = ""
    private var suggestionsCache: List<String> = emptyList()

    val isEmpty: Boolean get() = reading.isEmpty()
    val readingText: String get() = reading.toString()
    val segments: List<Segment> get() = segmentList

    /** What the text field should currently show. */
    val display: String
        get() = if (converting) segmentList.joinToString("") { it.text } else reading.toString()

    fun append(c: Char) {
        cancelConversion()
        reading.append(c)
    }

    /** Removes the last typed character; returns false if there was nothing to remove. */
    fun deleteLast(): Boolean {
        cancelConversion()
        if (reading.isEmpty()) return false
        reading.deleteCharAt(reading.length - 1)
        return true
    }

    fun lastChar(): Char? = reading.lastOrNull()

    fun replaceLast(c: Char) {
        cancelConversion()
        if (reading.isNotEmpty()) reading.setCharAt(reading.length - 1, c)
    }

    /** Converts the reading, or if already converting, moves to the next candidate of the focused 文節. */
    fun convertOrNext() {
        if (reading.isEmpty()) return
        if (!converting) {
            segmentList.clear()
            for (b in converter.convert(reading.toString())) segmentList.add(Segment(b.reading, b.candidates))
            focus = 0
            converting = true
        } else {
            step(1)
        }
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
        segmentList.clear()
        focus = 0
        converting = false
    }

    /** Whole-reading candidates offered while typing. The first is the best conversion of everything. */
    fun suggestions(): List<String> {
        val r = reading.toString()
        if (r.isEmpty()) return emptyList()
        if (r != suggestionsFor) {
            suggestionsFor = r
            suggestionsCache = converter.candidatesFor(r)
        }
        return suggestionsCache
    }

    /** Candidates the bar should list: suggestions while typing, the focused 文節's once converting. */
    fun barCandidates(): List<String> = if (converting) segmentList[focus].candidates else suggestions()
}
