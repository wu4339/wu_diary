package com.wu.diary.util

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Spannable
import android.text.Spanned
import android.text.TextPaint
import android.text.method.BaseMovementMethod
import android.text.style.ClickableSpan
import android.text.style.LineBackgroundSpan
import android.view.MotionEvent
import android.view.View
import android.widget.TextView

/**
 * 定位行：整行（含地名）底部画一条横线，并且可以点击打开地图。
 */
object LocationLines {

    /** 给包含坐标的行加底部横线；重复调用会先清掉旧的，方便编辑时刷新。 */
    fun decorate(text: Spannable, color: Int, thickness: Float) {
        text.getSpans(0, text.length, LocationLineSpan::class.java).forEach { text.removeSpan(it) }
        geoLineRanges(text).forEach { (start, end, _, _) ->
            text.setSpan(
                LocationLineSpan(color, thickness),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

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

    /** 每一条定位信息所在行的范围，以及对应的坐标。 */
    private fun geoLineRanges(text: Spannable): List<GeoLine> {
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
            // 下划线交给整行底部横线，不额外画文字下划线。
            ds.isUnderlineText = false
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
            val x = (event.x - widget.totalPaddingLeft + widget.scrollX).toInt()
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

    private class LocationLineSpan(
        private val color: Int,
        private val thickness: Float
    ) : LineBackgroundSpan {

        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        override fun drawBackground(
            canvas: Canvas,
            paint: Paint,
            left: Int,
            right: Int,
            top: Int,
            baseline: Int,
            bottom: Int,
            text: CharSequence,
            start: Int,
            end: Int,
            lineNumber: Int
        ) {
            linePaint.color = color
            val y = bottom - thickness
            canvas.drawRect(left.toFloat(), y, right.toFloat(), bottom.toFloat(), linePaint)
        }
    }
}
