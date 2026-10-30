package io.secure.keyboard.ime

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.util.Size
import android.view.View
import android.widget.*
import io.secure.keyboard.ui.KeyboardFeedback
import java.io.ByteArrayOutputStream

/** Ephemeral attachment. Never writes image data or a gallery cache to disk. */
internal object AssistantImages {
    fun allowed(c: Context): Boolean = c.checkSelfPermission(if (Build.VERSION.SDK_INT >= 33)
        Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED ||
        (Build.VERSION.SDK_INT >= 34 && c.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)

    fun encode(c: Context, uri: Uri, checkActive: () -> Unit = {}): String {
        checkActive()
        val bitmap = decode(c, uri, 1600)
        return try { checkActive(); ByteArrayOutputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it)
            checkActive()
            val bytes = it.toByteArray()
            try { checkActive(); "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP) }
            finally { bytes.fill(0) }
        } } finally { bitmap.recycle() }
    }

    private fun decode(c: Context, uri: Uri, size: Int): android.graphics.Bitmap {
        if (Build.VERSION.SDK_INT >= 28) return ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.contentResolver, uri)) { decoder, info, _ ->
            val ratio = minOf(1.0, size.toDouble() / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(maxOf(1, (info.size.width * ratio).toInt()), maxOf(1, (info.size.height * ratio).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        return decodeLegacy(c, uri, size)
    }

    internal fun decodeLegacy(c: Context, uri: Uri, size: Int): android.graphics.Bitmap {
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
        require(options.outWidth > 0 && options.outHeight > 0) { "无法读取图片" }
        options.inJustDecodeBounds = false
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > size) options.inSampleSize *= 2
        val bitmap = c.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
            ?: error("无法读取图片")
        val exif = runCatching { c.contentResolver.openInputStream(uri)?.use { android.media.ExifInterface(it) } }.getOrNull()
        val orientation = exif?.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) ?: 1
        val rotation = when(orientation) { 3, 4 -> 180; 6, 7 -> 90; 5, 8 -> 270; else -> 0 }
        val flipped = orientation in listOf(2, 4, 5, 7)
        if (rotation == 0 && !flipped) return bitmap
        val matrix = android.graphics.Matrix().apply { if (flipped) postScale(-1f, 1f); postRotate(rotation.toFloat()) }
        return android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if(it !== bitmap) bitmap.recycle() }
    }

    fun thumbnail(c: Context, uri: Uri, size: Int): android.graphics.Bitmap =
        if (Build.VERSION.SDK_INT >= 29) c.contentResolver.loadThumbnail(uri, Size(size, size), null) else decode(c, uri, size)

    /** Keyset metadata pages; only viewport cells and thumbnails are retained. */
    class Gallery(private val s: KeyboardSession) : HorizontalScrollView(s) {
        private val row = LinearLayout(s)
        private val executor = java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS, java.util.concurrent.LinkedBlockingQueue<Runnable>())
        private class Request {
            val signal = android.os.CancellationSignal()
            lateinit var task: java.util.concurrent.FutureTask<Unit>
        }
        private val requests = mutableMapOf<Int, Request>()
        private fun cancelRequest(index: Int) {
            requests.remove(index)?.let { request ->
                request.signal.cancel()
                request.task.cancel(true)
                executor.remove(request.task)
            }
        }
        private val cancellation = android.os.CancellationSignal()
        private val entries = mutableListOf<Uri>()
        private val cells = mutableMapOf<Int, ImageView>()
        private val pageSize = 25
        private var pageBusy = false
        private var end = false
        @Volatile private var closed = false
        private var beforeDate: Long? = null
        private var beforeId = Long.MAX_VALUE
        private val side = s.dp(64)
        private val stride = side + s.dp(8)
        init {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            addView(row)
            setOnScrollChangeListener { _: View, _: Int, _: Int, _: Int, _: Int -> refresh() }
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refresh() }
            loadPage()
        }
        private fun loadPage() {
            if (closed || pageBusy || end) return
            pageBusy = true
            val date = beforeDate
            val id = beforeId
            executor.execute {
                if (closed) return@execute
                val result = runCatching {
                    val args = Bundle().apply {
                        putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "date_added DESC, _id DESC")
                        putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, pageSize)
                        date?.let {
                            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "date_added < ? OR (date_added = ? AND _id < ?)")
                            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("$it", "$it", "$id"))
                        }
                    }
                    val items = mutableListOf<Pair<Long, Long>>()
                    s.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf("_id", "date_added"), args, cancellation)?.use {
                        while (items.size < pageSize && it.moveToNext()) items.add(it.getLong(0) to it.getLong(1))
                    }
                    items
                }
                s.main.post {
                    if (closed) return@post
                    pageBusy = false
                    result.onSuccess { items ->
                        end = items.size < pageSize
                        items.lastOrNull()?.let { beforeId = it.first; beforeDate = it.second }
                        entries.addAll(items.map { ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it.first) })
                        if (entries.isEmpty()) showMessage("没有可访问的图片") else refresh()
                    }.onFailure { end = true; if(entries.isEmpty()) showMessage("无法读取图片，请重新授权") }
                }
            }
        }
        private fun showMessage(message: String) {
            row.removeAllViews()
            row.addView(s.label(message, 12f, s.muted).apply { gravity = android.view.Gravity.CENTER },
                LinearLayout.LayoutParams(0, side, 1f))
        }
        private fun refresh() {
            if (closed || width <= 0 || entries.isEmpty()) return
            val first = ((scrollX / stride) - 1).coerceAtLeast(0)
            val last = ((scrollX + width) / stride + 1).coerceAtMost(entries.lastIndex)
            val removed = cells.keys.filter { it !in first..last }
            removed.forEach { cancelRequest(it); cells.remove(it)?.setImageDrawable(null) }
            val rebuild = removed.isNotEmpty() || (first..last).any { it !in cells } || row.tag != entries.size
            if (rebuild) {
                row.removeAllViews()
                row.addView(View(s), LinearLayout.LayoutParams(first * stride, side))
                for (index in first..last) {
                    val image = cells.getOrPut(index) { makeCell(index) }
                    row.addView(image, LinearLayout.LayoutParams(side, side).apply { rightMargin = s.dp(8) })
                }
                row.addView(View(s), LinearLayout.LayoutParams((entries.size - last - 1) * stride, side))
                row.tag = entries.size
            }
            if (entries.size * stride - scrollX - width < stride * 2) loadPage()
        }
        private fun makeCell(index: Int): ImageView {
            val uri = entries[index]
            val image = ImageView(s).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = s.shape(s.special, s.settings.keyCornerRadius)
                clipToOutline = true
                if (uri == s.aiImage) foreground = s.shape(android.graphics.Color.TRANSPARENT, s.settings.keyCornerRadius).apply { setStroke(s.dp(2), s.accent) }
                contentDescription = if (uri == s.aiImage) "已选择的图片" else "添加图片"
                KeyboardFeedback.disableAutomatic(this)
                val feedback = with(s) { installPressFeedback() }
                setOnClickListener {
                    if (!isAttachedToWindow || !s.active || !s.aiMode || s.secret || s.translation.busy) return@setOnClickListener
                    feedback.click()
                    s.aiImage = uri; s.aiDraftEpoch++; s.aiGalleryOpen = false
                    s.translation.invalidate(); s.renderBody(false)
                }
            }
            val request = Request()
            requests[index] = request
            request.task = java.util.concurrent.FutureTask<Unit> {
                if (!closed && !request.signal.isCanceled) {
                    val bitmap = runCatching {
                        if (Build.VERSION.SDK_INT >= 29) s.contentResolver.loadThumbnail(uri, Size(side, side), request.signal)
                        else thumbnail(s, uri, side)
                    }.getOrNull()
                    if (closed || request.signal.isCanceled) bitmap?.recycle()
                    else s.main.post {
                        if (!closed && requests[index] === request) {
                            requests.remove(index)
                            cells[index]?.setImageBitmap(bitmap) ?: bitmap?.recycle()
                        } else bitmap?.recycle()
                    }
                }
                Unit
            }
            executor.execute(request.task)
            return image
        }
        override fun onDetachedFromWindow() {
            closed = true; cancellation.cancel()
            requests.keys.toList().forEach(::cancelRequest)
            executor.shutdownNow()
            cells.values.forEach { it.setImageDrawable(null) }; cells.clear(); entries.clear()
            row.removeAllViews()
            super.onDetachedFromWindow()
        }
    }
}
