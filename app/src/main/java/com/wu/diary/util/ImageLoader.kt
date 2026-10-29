package com.wu.diary.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.util.LruCache
import android.view.ViewGroup
import android.widget.ImageView
import java.io.File

/** 本地图片按需采样解码，避免大图整张进内存。 */
object ImageLoader {

    // 大图模式会缓存整行宽度的图片，缓存给大一些，滚动时不必反复解码。
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 中图模式：固定尺寸的圆角卡片，不管原图多大都居中裁切铺满同一块区域。 */
    fun cardImage(context: Context, path: String, widthPx: Int, heightPx: Int): ImageView? {
        val bitmap = load(path, widthPx) ?: return null
        return ImageView(context).apply {
            layoutParams = ViewGroup.MarginLayoutParams(widthPx, heightPx)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(bitmap)
            val radius = 14 * context.resources.displayMetrics.density
            background = GradientDrawable().apply { cornerRadius = radius }
            clipToOutline = true
        }
    }

    /**
     * 大图模式的图片视图：按原图比例铺满给定宽度，圆角裁剪；
     * 过长时限制高度并居中裁切。
     */
    fun largeImage(context: Context, path: String, widthPx: Int, maxHeightPx: Int): ImageView? {
        val bitmap = load(path, widthPx) ?: return null
        if (bitmap.width <= 0) return null
        val exact = widthPx.toFloat() * bitmap.height / bitmap.width
        val capped = exact > maxHeightPx
        val height = if (capped) maxHeightPx else exact.toInt().coerceAtLeast(1)
        return ImageView(context).apply {
            layoutParams = ViewGroup.MarginLayoutParams(widthPx, height)
            scaleType = if (capped) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_XY
            setImageBitmap(bitmap)
            val radius = 14 * context.resources.displayMetrics.density
            background = GradientDrawable().apply { cornerRadius = radius }
            clipToOutline = true
        }
    }

    fun load(path: String?, targetPx: Int): Bitmap? {
        if (path.isNullOrEmpty() || targetPx <= 0) return null
        val key = "$path@$targetPx"
        cache.get(key)?.let { return it }
        if (!File(path).exists()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) {
            sample *= 2
        }
        val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        if (bitmap != null) cache.put(key, bitmap)
        return bitmap
    }
}
