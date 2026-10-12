package io.secure.keyboard.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Immutable, precompiled Wubi table. A read-only asset mapping owns the bytes; there are no
 * per-word Strings, Entry objects, hash buckets, query caches, or personal data at rest.
 * Only a requested candidate page is decoded. The same buffer is safe across lookup threads:
 * primitive reads are absolute, and text reads use a short-lived independent view.
 */
class CompactWubiTable(buffer: ByteBuffer) {
    private val bytes = buffer.asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN)
    val mappedByteCount: Int = bytes.capacity()
    val entryCount: Int
    private val exactCount: Int
    private val prefixCount: Int
    private val exactIndex: Int
    private val prefixIndex: Int
    private val prefixReferences: Int
    private val textData: Int

    init {
        require(buffer.position() == 0 && buffer.limit() == buffer.capacity()) { "Wubi asset requires a complete zero-based buffer" }
        require(bytes.capacity() >= HEADER_BYTES) { "Truncated Wubi asset header" }
        require((0 until 8).all { bytes.get(it) == MAGIC[it] }) { "Invalid Wubi asset magic" }
        require(bytes.getInt(8) == 1) { "Unsupported Wubi asset version" }
        entryCount = bytes.getInt(12)
        exactCount = bytes.getInt(16)
        prefixCount = bytes.getInt(20)
        exactIndex = bytes.getInt(24)
        prefixIndex = bytes.getInt(28)
        prefixReferences = bytes.getInt(32)
        textData = bytes.getInt(36)
        require(entryCount >= 0 && exactCount >= 0 && prefixCount >= 0) { "Invalid Wubi counts" }
        require(exactIndex == HEADER_BYTES) { "Invalid Wubi exact index offset" }
        require(prefixIndex.toLong() == exactIndex.toLong() + exactCount.toLong() * INDEX_BYTES) {
            "Invalid Wubi exact index extent"
        }
        require(prefixReferences.toLong() == prefixIndex.toLong() + prefixCount.toLong() * INDEX_BYTES) {
            "Invalid Wubi prefix index extent"
        }
        require(textData >= prefixReferences && textData <= bytes.capacity()) { "Invalid Wubi data offset" }
        require((textData - prefixReferences) % REFERENCE_BYTES == 0) { "Invalid Wubi reference extent" }
        // Every nonempty UTF-8 record consumes at least a two-byte length plus one byte.
        require(entryCount <= (bytes.capacity() - textData) / 3 && exactCount <= entryCount) {
            "Wubi entry counts exceed the available text data"
        }
        require(bytes.getInt(40) == bytes.capacity()) { "Invalid Wubi asset length" }
        require(bytes.getInt(44) == 30) { "Unsupported Wubi prefix limit" }
        require((48 until HEADER_BYTES).all { bytes.get(it).toInt() == 0 }) { "Invalid Wubi reserved header" }
    }

    fun exactCandidateCount(code: String): Int {
        val index = find(code, exactIndex, exactCount)
        return if (index < 0) 0 else countAt(index, false)
    }

    fun exact(code: String, limit: Int): List<Candidate> {
        if (limit <= 0) return emptyList()
        val index = find(code, exactIndex, exactCount)
        if (index < 0) return emptyList()
        val count = minOf(countAt(index, false), limit)
        var offset = bytes.getInt(index + 4)
        val result = ArrayList<Candidate>(count)
        repeat(count) {
            val length = textLength(offset)
            result.add(Candidate(readText(offset, length), code, Source.WUBI))
            offset += 2 + length
        }
        return result
    }

    fun prefix(code: String, limit: Int): List<Candidate> {
        if (limit <= 0 || code.length >= 4) return emptyList()
        val index = find(code, prefixIndex, prefixCount)
        if (index < 0) return emptyList()
        val count = minOf(countAt(index, true), limit)
        val first = bytes.getInt(index + 4)
        val start = prefixReferences.toLong() + first.toLong() * REFERENCE_BYTES
        require(first >= 0 && start + count.toLong() * REFERENCE_BYTES <= textData.toLong()) {
            "Invalid Wubi prefix reference"
        }
        val result = ArrayList<Candidate>(count)
        repeat(count) { row ->
            val offset = start.toInt() + row * REFERENCE_BYTES
            val textOffset = bytes.getInt(offset)
            result.add(Candidate(readText(textOffset, textLength(textOffset)), decodeCode(bytes.getInt(offset + 4)), Source.WUBI))
        }
        return result
    }

    private fun countAt(index: Int, prefix: Boolean): Int {
        val count = bytes.getInt(index + 8)
        require(count >= 0 && count <= (if (prefix) 30 else entryCount)) { "Invalid Wubi bucket count" }
        return count
    }

    private fun find(code: String, start: Int, count: Int): Int {
        val key = encodeCode(code)
        if (key < 0) return -1
        var low = 0
        var high = count - 1
        while (low <= high) {
            val mid = (low + high).ushr(1)
            val offset = start + mid * INDEX_BYTES
            val stored = bytes.getInt(offset)
            when {
                stored < key -> low = mid + 1
                stored > key -> high = mid - 1
                else -> return offset
            }
        }
        return -1
    }

    private fun textLength(offset: Int): Int {
        require(offset >= textData && offset <= bytes.capacity() - 2) { "Invalid Wubi text offset" }
        val length = bytes.getShort(offset).toInt() and 0xffff
        require(length > 0 && offset.toLong() + 2L + length <= bytes.capacity().toLong()) {
            "Invalid Wubi text length"
        }
        return length
    }

    private fun readText(offset: Int, length: Int): String {
        val data = ByteArray(length)
        val view = bytes.duplicate()
        // Use Buffer's original signature rather than newer covariant ByteBuffer overloads.
        (view as java.nio.Buffer).position(offset + 2)
        view.get(data)
        return String(data, Charsets.UTF_8)
    }

    companion object {
        private val MAGIC = "SKWB0001".toByteArray(Charsets.US_ASCII)
        private const val HEADER_BYTES = 64
        private const val INDEX_BYTES = 12
        private const val REFERENCE_BYTES = 8

        private fun encodeCode(code: String): Int {
            if (code.isEmpty() || code.length > 4 || code.any { it !in 'a'..'z' }) return -1
            var key = 0
            repeat(4) { index -> key = key * 27 + if (index < code.length) code[index] - 'a' + 1 else 0 }
            return key
        }

        private fun decodeCode(key: Int): String {
            require(key in 1 until 531441) { "Invalid Wubi encoded key" }
            var remaining = key
            var divisor = 19683
            val result = StringBuilder(4)
            repeat(4) {
                val digit = remaining / divisor
                remaining %= divisor
                divisor /= 27
                if (digit != 0) result.append(('a'.code + digit - 1).toChar())
                else require(remaining == 0) { "Invalid Wubi code padding" }
            }
            return result.toString()
        }
    }
}
