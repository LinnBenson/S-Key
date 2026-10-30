package io.secure.keyboard.data

import android.content.res.AssetManager
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Immutable build-time trie: opening the converter never parses the text dictionaries. */
internal class TraditionalConverter private constructor(private val data: ByteBuffer) {
    private val edgeStart = 16 + data.getInt(4) * 16

    fun convert(text: String): String = buildString(text.length) {
        var start = 0
        while (start < text.length) {
            var node = 0
            var cursor = start
            var matchedEnd = start
            var valueOffset = 0
            var valueLength = 0
            while (cursor < text.length) {
                val record = 16 + node * 16
                var low = data.getInt(record)
                var high = low + data.getInt(record + 4) - 1
                var child = -1
                val unit = text[cursor].code
                while (low <= high) {
                    val mid = (low + high) ushr 1
                    val edge = edgeStart + mid * 8
                    val key = data.getInt(edge)
                    when {
                        key < unit -> low = mid + 1
                        key > unit -> high = mid - 1
                        else -> { child = data.getInt(edge + 4); break }
                    }
                }
                if (child < 0) break
                node = child
                cursor++
                val length = data.getInt(16 + node * 16 + 12)
                if (length > 0) {
                    matchedEnd = cursor
                    valueOffset = data.getInt(16 + node * 16 + 8)
                    valueLength = length
                }
            }
            if (matchedEnd == start) append(text[start++]) else {
                repeat(valueLength) { append(data.getChar(valueOffset + it * 2)) }
                start = matchedEnd
            }
        }
    }

    companion object {
        @Volatile private var shared: TraditionalConverter? = null
        fun shared(assets: AssetManager): TraditionalConverter = shared ?: synchronized(this) {
            shared ?: assets.openFd("opencc/conversion.skt").use { asset ->
                FileInputStream(asset.fileDescriptor).use { stream ->
                    val data = stream.channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.length)
                        .order(ByteOrder.LITTLE_ENDIAN)
                    check(data.getInt(0) == 0x534b5431)
                    TraditionalConverter(data)
                }
            }.also { shared = it }
        }
        @Synchronized fun trimMemory() { shared = null }
    }
}
