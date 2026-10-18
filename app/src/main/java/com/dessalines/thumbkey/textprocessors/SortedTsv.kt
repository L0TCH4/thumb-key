package com.dessalines.thumbkey.textprocessors

/**
 * Lookup table over a `key<TAB>value<TAB>value...` text file whose lines are sorted by the UTF-8
 * bytes of the key. Works directly on the raw bytes so a few hundred thousand entries cost little
 * more than the file itself.
 */
class SortedTsv(
    private val bytes: ByteArray,
) {
    private val lineStarts: IntArray

    init {
        var count = 0
        for (b in bytes) if (b == NEWLINE) count++
        lineStarts = IntArray(count)
        var line = 0
        var start = 0
        for (i in bytes.indices) {
            if (bytes[i] == NEWLINE) {
                lineStarts[line++] = start
                start = i + 1
            }
        }
    }

    val size: Int get() = lineStarts.size

    /** The values stored for [key], or an empty list. */
    fun lookup(key: String): List<String> {
        val k = key.toByteArray(Charsets.UTF_8)
        var lo = 0
        var hi = lineStarts.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val cmp = compareKey(lineStarts[mid], k)
            if (cmp < 0) {
                lo = mid + 1
            } else if (cmp > 0) {
                hi = mid - 1
            } else {
                return values(lineStarts[mid])
            }
        }
        return emptyList()
    }

    /** Compares the key of the line starting at [start] with [key]; negative if the line sorts first. */
    private fun compareKey(
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

    private fun values(start: Int): List<String> {
        var end = start
        while (bytes[end] != NEWLINE) end++
        return String(bytes, start, end - start, Charsets.UTF_8).split('\t').drop(1)
    }

    private companion object {
        const val NEWLINE = '\n'.code.toByte()
        const val TAB = '\t'.code.toByte()
    }
}
