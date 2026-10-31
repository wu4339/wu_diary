package com.wu.diary.util

import android.content.Context
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.URLSpan
import android.text.util.Linkify
import android.view.View

/**
 * 正文链接样式：网址与定位行统一成紫色下划线，并在末尾追加一个灰色的「↗」跳转符号。
 */
object LinkStyles {

    /** 跳转符号的颜色。 */
    private val ARROW_COLOR = 0xFF8A8A8A.toInt()

    /** 跳转符号。 */
    private const val ARROW = "\u2197"

    fun decorate(source: Spannable): Spannable {
        val spannable = SpannableStringBuilder(source)
        Linkify.addLinks(spannable, Linkify.WEB_URLS)
        val arrowPositions = mutableListOf<Int>()
        spannable.getSpans(0, spannable.length, URLSpan::class.java).forEach { old ->
            val start = spannable.getSpanStart(old)
            val end = spannable.getSpanEnd(old)
            val flags = spannable.getSpanFlags(old)
            val url = old.url
            spannable.removeSpan(old)
            spannable.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    LinkOpener.open(widget.context, android.net.Uri.parse(url))
                }

                override fun updateDrawState(ds: TextPaint) {
                    super.updateDrawState(ds)
                    ds.color = LocationLines.LINK_COLOR
                    ds.isUnderlineText = true
                }
            }, start, end, flags)
            arrowPositions.add(end)
        }
        // 定位行整行都可点击，样式与网址一致。
        LocationLines.link(spannable)
        arrowPositions.addAll(LocationLines.lineEnds(spannable))
        // 从后往前插入「 ↗」，避免前面的位置偏移。
        arrowPositions.distinct().sortedDescending().forEach { position ->
            val arrow = " $ARROW"
            spannable.insert(position, arrow)
            spannable.setSpan(
                ForegroundColorSpan(ARROW_COLOR),
                position + 1,
                position + 1 + ARROW.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return spannable
    }
}
