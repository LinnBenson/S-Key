package io.secure.keyboard.ime

import android.content.*
import android.os.*
import android.view.*
import android.view.inputmethod.InputConnection
import android.widget.*
import kotlin.math.*
import io.secure.keyboard.data.ContentStore
import io.secure.keyboard.data.DictionaryKind
import io.secure.keyboard.engine.CustomCandidateRanking
import io.secure.keyboard.engine.EnglishCustomCandidateRanking
import io.secure.keyboard.engine.Source
import io.secure.keyboard.ui.CandidateFont

import io.secure.keyboard.*

/** Candidate queries and stale-result checks. */
internal object CandidateController {
    internal fun KeyboardSession.clearCandidatesImpl() {
        candidateTexts = emptyList()
        candidateQuery = ""
        candidateGeneration = -1
        candidateConnection = null
        candidateDictionaryKind = null
        candidateDictionaryRevision = null
        candidateGrid?.updateCandidates(emptyList())
    }

    internal fun KeyboardSession.englishPrefixImpl(connection: InputConnection?): String =
        try {
            readEnglishPrefix(connection)
        } catch (_: RuntimeException) {
            // An unavailable or unsupported host connection must never take down the keyboard.
            ""
        }

    internal fun KeyboardSession.readEnglishPrefixImpl(connection: InputConnection?): String {
        fun continuesToken(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c.isDigit() || c in "_'’"
        if (connection == null || !connection.getSelectedText(0).isNullOrEmpty()) return ""
        // Read the prefix before validating the boundary. An asynchronous host can move the
        // cursor while servicing these calls; never combine an old end-of-word boundary with
        // a newer prefix from the middle of a word. Unknown host state suppresses completion.
        val before = connection.getTextBeforeCursor(41, 0)?.toString() ?: return ""
        val after = connection.getTextAfterCursor(1, 0) ?: return ""
        if (after.firstOrNull()?.let { continuesToken(it) } == true ||
            !connection.getSelectedText(0).isNullOrEmpty()) return ""
        // One extra character distinguishes a complete 40-letter code from a longer token's tail.
        val prefix = before.takeLastWhile { it in 'a'..'z' || it in 'A'..'Z' }
        if (prefix.length > 40) return ""
        val preceding = before.getOrNull(before.length - prefix.length - 1)
        if (preceding != null && (preceding.isDigit() || preceding in "_'’")) return ""
        return prefix
    }

    internal fun KeyboardSession.assistantEnglishPrefixImpl(editor: EditText): String {
        val cursor=editor.selectionStart
        if(cursor<0 || cursor!=editor.selectionEnd) return ""
        val text=editor.text
        val next=text.getOrNull(cursor)
        if(next!=null && (next.isLetterOrDigit() || next in "_'’")) return ""
        val before=text.substring(0,cursor).takeLast(41)
        val prefix=before.takeLastWhile { it in 'a'..'z' || it in 'A'..'Z' }
        if(prefix.length>40) return ""
        val preceding=before.getOrNull(before.length-prefix.length-1)
        return if(preceding!=null && (preceding.isDigit() || preceding in "_'’")) "" else prefix
    }

    internal fun KeyboardSession.refreshCandidatesImpl() {
        val seq = ++generation
        val zh = chinese
        val kind = if (zh) DictionaryKind.CHINESE else DictionaryKind.ENGLISH
        val localCode = code
        val connection = currentInputConnection
        val localEditor = activeAssistantEditor()
        val localPrefix = localEditor?.let { assistantEnglishPrefix(it) }
        // Keep the previous row visible while its replacement is computed. Its generation is now
        // stale, so neither a tap nor Space can commit it. Privacy changes are the exception.
        if (candidateDictionaryKind?.let { candidateDictionaryRevision != content.dictionaryRevision(it) } == true)
            clearCandidates()
        if (
            (translation.enabled && !zh && localEditor==null) || (zh && localCode.isEmpty()) || !loaded || secret || (!zh && !settings.englishCompletion)
        ) {
            candidateQueries.clear()
            clearCandidates()
            renderHeader()
            return
        }
        renderHeader()
        val fuzzy = settings.fuzzyPinyin
        val fuzzyPairs = settings.fuzzyPairs
        val candidatePaint = candidateStrip.normalPaintSnapshot()
        candidateQueries.submit(onRejected = { candidateQueryFailed(seq, zh, connection) }) query@{
            try {
            if (destroyed || seq != generation) return@query
            // WebView and other asynchronous editors may need their main thread to answer.
            // Never block the IME/main thread with synchronous surrounding-text requests.
            val query = if (zh) localCode else localPrefix ?: englishPrefix(connection)
            if (destroyed || seq != generation) return@query
            val dictionaryRevision = content.dictionaryRevision(kind)
            val runtimeRevision = engine.revision
            val user = readContent(emptyList<ContentStore.DictionaryEntry>()) {
                content.dictionary(kind)
            }
            val results =
                if (zh) {
                    val base = engine.candidates(query, fuzzy, 40, fuzzyPairs)
                    val baseline =
                        base.filter { it.source == Source.WUBI }.map { it.text } +
                            base.filter { it.source != Source.WUBI }.map { it.text }
                    engine.appendEmoji(CustomCandidateRanking.merge(
                        query, baseline, user,
                        reading = { it.reading }, text = { it.word },
                    ))
                } else EnglishCustomCandidateRanking.merge(
                    query, engine.completeEnglish(query, 12).map { it.text }, user,
                    reading = { it.reading }, text = { it.word },
                )
            if (destroyed || seq != generation) return@query
            CandidateFont.prepare(this, results, candidatePaint)
            if (destroyed || seq != generation) return@query
            main.post {
                if (
                    seq == generation && active && chinese == zh &&
                        currentInputConnection === connection &&
                        (zh || localEditor===activeAssistantEditor())
                ) {
                    if (
                        secret || (!zh && !settings.englishCompletion) ||
                            dictionaryRevision != content.dictionaryRevision(kind) || runtimeRevision != engine.revision
                    ) {
                        // A clear/edit must not resurrect a previously captured user entry.
                        clearCandidates()
                        renderHeader()
                        if (pendingConfirmation != null) refreshCandidates()
                        return@post
                    }
                    candidateRuntimeRevision = runtimeRevision
                    candidateTexts = results
                    candidateQuery = query
                    candidateGeneration = seq
                    candidateConnection = connection
                    candidateDictionaryKind = kind
                    candidateDictionaryRevision = dictionaryRevision
                    renderHeader()
                    finishPendingConfirmation()
                }
            }
            } catch (_: Exception) {
                candidateQueryFailed(seq, zh, connection)
            }
        }
    }

    internal fun KeyboardSession.candidateQueryFailedImpl(seq: Int, zh: Boolean, connection: InputConnection?) {
        main.post {
            if (!destroyed && seq == generation && active && chinese == zh &&
                currentInputConnection === connection) {
                clearCandidates()
                renderHeader()
                if (pendingConfirmation != null) {
                    toast("候选查询失败，可修改编码或按回车输入编码")
                    releasePendingConfirmation()
                }
            }
        }
    }

}
