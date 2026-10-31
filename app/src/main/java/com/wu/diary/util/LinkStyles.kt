package com.wu.diary.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.ImageSpan
import android.text.style.URLSpan
import android.text.util.Linkify
import android.view.View
import androidx.core.content.ContextCompat
import com.wu.diary.R

/**
 * 正文链接样式：网址与定位行统一成紫色下划线，并在末尾追加一个灰色的跳转图标。
 */
object LinkStyles {

    /** 网址链接 span，编辑页刷新时用这个类型清掉旧的。 */
    class UrlLinkSpan(private val url: String) : ClickableSpan() {
        override fun onClick(widget: View) {
            LinkOpener.open(widget.context, Uri.parse(url))
        }

        override fun updateDrawState(ds: TextPaint) {
            super.updateDrawState(ds)
            ds.color = LocationLines.LINK_COLOR
            ds.isUnderlineText = true
        }
    }

    /**
     * 给一段文本套用链接样式（网址 + 定位行），并在每个链接后面追加跳转图标。
     * 返回新的 Spannable，不修改传入对象。
     */
    fun decorate(context: Context, source: Spannable): Spannable {
        val spannable = SpannableStringBuilder(source)
        linkUrls(spannable)
        // 定位行整行都可点击，样式与网址一致。
        LocationLines.link(spannable)
        val arrowPositions = mutableListOf<Int>()
        spannable.getSpans(0, spannable.length, UrlLinkSpan::class.java).forEach { span ->
            arrowPositions.add(spannable.getSpanEnd(span))
        }
        arrowPositions.addAll(LocationLines.lineEnds(spannable))
        insertArrows(context, spannable, arrowPositions)
        return spannable
    }

    /** 就地给文本里的网址套用链接样式（编辑页使用，不改动文字内容）。 */
    fun linkUrls(text: Spannable) {
        text.getSpans(0, text.length, UrlLinkSpan::class.java).forEach { text.removeSpan(it) }
        Linkify.addLinks(text, Linkify.WEB_URLS)
        text.getSpans(0, text.length, URLSpan::class.java).toList().forEach { old ->
            val start = text.getSpanStart(old)
            val end = text.getSpanEnd(old)
            val flags = text.getSpanFlags(old)
            val url = old.url
            text.removeSpan(old)
            text.setSpan(UrlLinkSpan(url), start, end, flags)
        }
    }

    /** 在每个链接末尾插入空格 + 跳转图标，从后往前插入避免位置偏移。 */
    private fun insertArrows(
        context: Context,
        spannable: SpannableStringBuilder,
        positions: List<Int>
    ) {
        val icon = arrowIcon(context)
        positions.distinct().sortedDescending().forEach { position ->
            // 第一个空格留出间隔，第二个空格换成图标。
            spannable.insert(position, "  ")
            spannable.setSpan(
                ImageSpan(icon, ImageSpan.ALIGN_CENTER),
                position + 1,
                position + 2,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    /** 用位图渲染图标，避免部分机型上矢量 Drawable 作 ImageSpan 时不显示。 */
    private fun arrowIcon(context: Context): BitmapDrawable {
        val density = context.resources.displayMetrics.density
        val size = (16 * density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_external_link)
        if (drawable != null) {
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bitmap))
        }
        return BitmapDrawable(context.resources, bitmap)
    }
}
