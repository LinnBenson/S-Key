package io.secure.keyboard.dictionaries

import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.nio.charset.CodingErrorAction

/** Source files remain authoritative; editing preserves untouched headers, comments and lines. */
object DictionarySource {
    data class Page(val start: Int,val count: Int,val text: String,val hasNext: Boolean,val version: String)
    fun reader(input: InputStream) = java.io.InputStreamReader(input, Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)).buffered()
    private fun BufferedReader.rawLine(): String? {
        val line=StringBuilder()
        while(true) {
            val c=read()
            if(c<0) return line.toString().takeIf { it.isNotEmpty() }
            line.append(c.toChar())
            require(line.length<=8192) { "单行不能超过 8192 个字符" }
            if(c==10) return line.toString()
        }
    }
    fun page(file: File,start: Int,version: String): Page = reader(file.inputStream()).use { r ->
        var skipped=0
        while(skipped<start && r.rawLine()!=null) skipped++
        val text=StringBuilder(); var count=0
        while(count<120 && text.length<128_000) {
            val line=r.rawLine() ?: break
            text.append(line); count++
        }
        Page(skipped,count,text.toString(),r.read()!=-1,version)
    }
    fun replace(file: File,page: Page,text: String,output: File) {
        require(text.lineSequence().all { it.length<=8192 }) { "单行不能超过 8192 个字符" }
        require(text.length<=256_000) { "单次编辑内容过大，请分段保存" }
        reader(file.inputStream()).use { r -> output.writer().buffered().use { w ->
            repeat(page.start) { w.write(r.rawLine() ?: error("文件已变化，请重新打开")) }
            repeat(page.count) { check(r.rawLine()!=null) { "文件已变化，请重新打开" } }
            val next=r.rawLine()
            w.write(text)
            if(next!=null && text.isNotEmpty() && !text.endsWith('\n')) w.write("\n")
            if(next!=null) w.write(next)
            while(true) { val line=r.rawLine() ?: break; w.write(line) }
        } }
    }
    fun copy(input: InputStream,output: File) = input.use { source -> output.outputStream().use { out ->
        val buffer=ByteArray(8192); var size=0L
        while(true) { val count=source.read(buffer); if(count<0) break; size+=count; require(size<=64*1024*1024) { "词库文件不能超过 64 MiB" }; out.write(buffer,0,count) }
    } }.also {
        // Validate encoding/line bounds, but leave syntax validation to Compile.
        reader(output.inputStream()).use { r -> while(r.rawLine()!=null) {} }
    }
    fun find(file: File,query: String,after: Int): Int? = reader(file.inputStream()).use { r ->
        var line=0
        while(true) { val text=r.rawLine() ?: break; if(line>=after && text.contains(query,ignoreCase=true)) return@use line; line++ }
        null
    }
    fun parse(file: File,kind: String,filename: String,emit: (String,String,Long)->Unit) = reader(file.inputStream()).use { r ->
        val yaml=filename.endsWith(".yaml",true) || filename.endsWith(".yml",true)
        var started=!yaml; var lineNumber=0
        while(true) {
            val raw=r.rawLine() ?: break; lineNumber++
            val line=raw.trimEnd('\n','\r').removePrefix("\uFEFF")
            if(!started) { if(line.trim()=="...") started=true; continue }
            if(line.isBlank() || line.startsWith('#')) continue
            try {
                val parts=line.split('\t')
                require(parts.size>=2) { "字段须以制表符分隔" }
                val text=parts[0]; val code=if(kind=="english") text else parts[1]
                val weightText=if(kind=="english") parts[1] else parts.getOrNull(2) ?: "0"
                // Preserve the pinned Rime baseline's documented legacy fallback exactly.
                val weight=weightText.toLongOrNull() ?: if(text=="χ" && code=="zzxx" && weightText=="450.5") 0L else error("词频必须为整数")
                DictionaryCompiler.validate(kind,text,code,weight)
                emit(text,code,weight)
            } catch(e: Exception) { throw IllegalArgumentException("$filename 第 $lineNumber 行：${e.message}",e) }
        }
        require(started) { "$filename：YAML 词库缺少 ... 头部结束标记" }
    }
}
