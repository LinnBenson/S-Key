package io.secure.keyboard.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The document picker creates its output before the password dialog is confirmed. */
internal object CanceledBackupDocument {
    fun discard(context: Context, uri: Uri) {
        val app = context.applicationContext
        // Cleanup must survive the password dialog's Activity being destroyed or rotated.
        CoroutineScope(Dispatchers.IO).launch {
            val cleaned = runCatching {
                deleteIfEmpty({ app.contentResolver.openInputStream(uri) }) {
                    DocumentsContract.deleteDocument(app.contentResolver, uri)
                }
            }.getOrDefault(false)
            if (!cleaned) withContext(Dispatchers.Main) {
                Toast.makeText(app, "无法清理取消的空备份文件，请在文件管理器中删除", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Never delete a nonempty document, including an existing file selected for overwrite. */
    internal fun deleteIfEmpty(open: () -> InputStream?, delete: () -> Boolean): Boolean {
        val empty = open()?.use { it.read() == -1 } ?: return false
        return !empty || delete()
    }
}
