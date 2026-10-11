package com.auralis.core.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val artworkCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

suspend fun loadArtworkThumbnail(path: String?, targetPx: Int = 320): Bitmap? = withContext(Dispatchers.IO) {
    if (path.isNullOrBlank()) return@withContext null
    artworkCache.get(path)?.let { return@withContext it }
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > targetPx * 2 || bounds.outHeight / sample > targetPx * 2) sample *= 2
        val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565
        }) ?: return@runCatching null
        artworkCache.put(path, bitmap)
        bitmap
    }.getOrNull()
}
