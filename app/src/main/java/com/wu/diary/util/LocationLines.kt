package com.wu.diary.util

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Spannable
import android.text.Spanned
import android.text.style.LineBackgroundSpan

/**
 * 定位行下划线：在文本里包含坐标的整行下方画一条横线，地名也一起覆盖。
 */
object LocationLines {

    /** 给包含坐标的行加底部横线；重复调用会先清掉旧的，方便编辑时刷新。 */
    fun decorate(text: Spannable, color: Int, thickness: Float) {
        text.getSpans(0, text.length, LocationLineSpan::class.java).forEach { text.removeSpan(it) }
        GeoPoint.findAll(text.toString()).forEach { match ->
            val lineStart = text.lastIndexOf('\n', match.start).let { if (it < 0) 0 else it + 1 }
            val lineEnd = text.indexOf('\n', match.end).let { if (it < 0) text.length else it }
            if (lineStart >= lineEnd) return@forEach
            text.setSpan(
                LocationLineSpan(color, thickness),
                lineStart,
                lineEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
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
