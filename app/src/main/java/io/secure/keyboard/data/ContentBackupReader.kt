package io.secure.keyboard.data

import android.util.JsonReader
import android.util.JsonToken
import java.io.FilterReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import org.json.JSONArray
import org.json.JSONObject

/** Fixed-depth streaming schema: reject excess rows before allocating a whole untrusted tree. */
internal object ContentBackupReader {
    fun read(input: InputStream): JSONObject {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val bounded = object : FilterReader(InputStreamReader(input, decoder)) {
            var total = 0L
            override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, length)
                if (count > 0) { total += count; require(total <= 12_000_000) }
                return count
            }
        }
        return JsonReader(bounded).use { reader ->
            val root = JSONObject(); val fields = HashSet<String>()
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName(); require(fields.add(name))
                when (name) {
                    "format" -> { require(reader.peek() == JsonToken.STRING); require(reader.nextString() == "skey-content-backup"); root.put(name, "skey-content-backup") }
                    "version" -> { require(reader.peek() == JsonToken.NUMBER && reader.nextString() == "1"); root.put(name, 1) }
                    "data" -> root.put(name, data(reader))
                    else -> error("Unknown backup field")
                }
            }
            reader.endObject()
            require(fields == setOf("format", "version", "data") && reader.peek() == JsonToken.END_DOCUMENT)
            root
        }
    }
    private fun data(reader: JsonReader): JSONObject {
        val result = JSONObject(); val seen = HashSet<String>()
        val keys = setOf("dictionary", "englishDictionary", "phrases", "aiPresets")
        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName(); require(key in keys && seen.add(key))
            val rows = JSONArray(); val maxRows = if (key == "aiPresets") 100 else 10_000
            val allowed = when (key) {
                "aiPresets" -> setOf("id", "name", "prompt", "reasoningEffort")
                "phrases" -> setOf("id", "text", "pinned")
                else -> setOf("id", "word", "reading")
            }
            reader.beginArray()
            while (reader.hasNext()) {
                require(rows.length() < maxRows)
                val row = JSONObject(); val fields = HashSet<String>()
                reader.beginObject()
                while (reader.hasNext()) {
                    val field = reader.nextName(); require(field in allowed && fields.add(field))
                    if (field == "pinned") {
                        require(reader.peek() == JsonToken.BOOLEAN); row.put(field, reader.nextBoolean())
                    } else {
                        require(reader.peek() == JsonToken.STRING)
                        val value = reader.nextString()
                        val max = when (field) { "id" -> 128; "name" -> 40; "prompt" -> 2000; "reasoningEffort" -> 6; "text" -> 1_000_000; else -> 4_000_000 }
                        require(value.length <= max); row.put(field, value)
                    }
                }
                reader.endObject(); require(fields.containsAll(allowed - setOf("pinned", "reasoningEffort"))); rows.put(row)
            }
            reader.endArray(); result.put(key, rows)
        }
        reader.endObject(); require(seen == keys)
        return result
    }
}
