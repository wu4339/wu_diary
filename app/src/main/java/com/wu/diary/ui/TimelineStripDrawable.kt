package com.wu.diary.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * 时间线风格的列表底色：整行米色底 + 左侧贯穿竖线，
 * 日期头所在行还会在竖线上画一个空心圆点。
 */
class TimelineStripDrawable(
    private val background: Int,
    private val density: Float,
    private val dotCenterY: Float = 0f
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val b = bounds
        paint.style = Paint.Style.FILL
        paint.color = background
        canvas.drawRect(b, paint)

        // 竖线穿过整行，行与行首尾相接拼成一条连续的时间轴。
        val x = 24f * density
        paint.style = Paint.Style.STROKE
        paint.color = LINE_COLOR
        paint.strokeWidth = 2f * density
        canvas.drawLine(x, 0f, x, b.height().toFloat(), paint)

        if (dotCenterY > 0f) {
            paint.style = Paint.Style.STROKE
            paint.color = LINE_COLOR
            paint.strokeWidth = 2f * density
            canvas.drawCircle(x, dotCenterY * density, 6f * density, paint)
        }
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** 米色底。 */
        val BACKGROUND: Int = 0xFFE3DDD2.toInt()

        /** 时间轴与圆点的颜色。 */
        val LINE_COLOR: Int = 0xFF1C1C1C.toInt()
    }
}
