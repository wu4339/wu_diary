package com.wu.diary.util

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.LruCache
import java.io.File

/** 本地视频按需截取封面帧并缓存，避免每次渲染都重新解码。 */
object VideoLoader {

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 取视频约 0.5 秒处的一帧，缩放到目标宽度后作为封面。 */
    fun thumbnail(path: String?, widthPx: Int): Bitmap? {
        if (path.isNullOrEmpty() || widthPx <= 0) return null
        val key = "$path@$widthPx"
        cache.get(key)?.let { return it }
        if (!File(path).exists()) return null

        val retriever = MediaMetadataRetriever()
        val frame = runCatching {
            retriever.setDataSource(path)
            retriever.getFrameAtTime(500_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }.getOrNull()
        runCatching { retriever.release() }
        if (frame == null || frame.width <= 0) return null

        val scaled = if (frame.width > widthPx) {
            val height = (frame.height.toLong() * widthPx / frame.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(frame, widthPx, height, true)
        } else {
            frame
        }
        cache.put(key, scaled)
        return scaled
    }
}
