package io.secure.keyboard.data

import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Strict, bounded decoding before any user-dictionary mutation. Owns and closes the reader. */
object DictionaryTextReader {
    fun read(input: InputStream, maxCharacters: Int = 4_000_000): String {
        require(maxCharacters >= 0)
        try {
            val decoder =
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
            return InputStreamReader(input, decoder).buffered().use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(8192)
                var read = reader.read(buffer)
                while (read >= 0) {
                    require(read <= maxCharacters - result.length) {
                        "词库文件超过 400 万字符限制，请拆分后重试。本次未导入任何词条。"
                    }
                    result.append(buffer, 0, read)
                    read = reader.read(buffer)
                }
                result.toString()
            }
        } catch (_: CharacterCodingException) {
            error("文件不是有效的 UTF-8 文本，请转换为 UTF-8 后重试。本次未导入任何词条。")
        }
    }
}
