package com.wu.diary.util

import android.text.Spannable
import android.text.Spanned
import android.text.TextPaint
import android.text.method.BaseMovementMethod
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.view.View
import android.widget.TextView

/**
 * 定位行链接：整行（含地名）使用和网址一致的紫色下划线样式，点击打开地图。
 */
object LocationLines {

    /** 链接颜色（紫色），与网址链接保持一致。 */
    val LINK_COLOR = 0xFF7C5CE6.toInt()

    /** 整行定位信息可点击：点地名或坐标都打开地图。 */
    fun link(text: Spannable) {
        text.getSpans(0, text.length, GeoLinkSpan::class.java).forEach { text.removeSpan(it) }
        geoLineRanges(text).forEach { (start, end, lat, lng) ->
            text.setSpan(
                GeoLinkSpan(lat, lng),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    /** 每条定位信息所在行的末尾位置，用于在链接后面插入外链小图标。 */
    fun lineEnds(text: CharSequence): List<Int> =
        GeoPoint.findAll(text.toString())
            .map { match ->
                val end = text.indexOf('\n', match.end)
                if (end < 0) text.length else end
            }
            .distinct()

    private fun geoLineRanges(text: CharSequence): List<GeoLine> {
        return GeoPoint.findAll(text.toString())
            .mapNotNull { match ->
                val start = text.lastIndexOf('\n', match.start).let { if (it < 0) 0 else it + 1 }
                val end = text.indexOf('\n', match.end).let { if (it < 0) text.length else it }
                if (start >= end) null else GeoLine(start, end, match.lat, match.lng)
            }
    }

    private data class GeoLine(val start: Int, val end: Int, val lat: Double, val lng: Double)

    private class GeoLinkSpan(
        private val lat: Double,
        private val lng: Double
    ) : ClickableSpan() {

        override fun onClick(widget: View) {
            GeoPoint.openMap(widget.context, lat, lng)
        }

        override fun updateDrawState(ds: TextPaint) {
            super.updateDrawState(ds)
            ds.color = LINK_COLOR
            ds.isUnderlineText = true
        }
    }

    /**
     * 编辑页用：点中定位行打开地图，点其他位置仍然按普通文本放光标，
     * 这样既保留了链接，也不会影响编辑。
     */
    class LinkMovementMethod : BaseMovementMethod() {

        override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
            if (event.actionMasked != MotionEvent.ACTION_UP) return false
            val layout = widget.layout ?: return false
            val x = event.x - widget.totalPaddingLeft + widget.scrollX
            val y = (event.y - widget.totalPaddingTop + widget.scrollY).toInt()
            val line = layout.getLineForVertical(y)
            if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return false
            val offset = layout.getOffsetForHorizontal(line, x)
            val span = buffer.getSpans(offset, offset, ClickableSpan::class.java)
                .firstOrNull { it is GeoLinkSpan } ?: return false
            span.onClick(widget)
            return true
        }
    }
}
