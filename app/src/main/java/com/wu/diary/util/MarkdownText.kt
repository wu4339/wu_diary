package com.wu.diary.util

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Spannable
import android.text.Spanned
import android.text.style.ReplacementSpan

/**
 * 正文里的 Markdown 无序列表（- / * / +）渲染成灰色圆点，
 * 同时记录显示字符与原文字符的对应关系，双击定位时仍能落在正确的光标处。
 */
object MarkdownText {

    private val listLine = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private const val BULLET_COLOR = 0xFF9E9E9E.toInt()

    /** [bullets] 是每个圆点在显示文本中的起始位置与缩进层级。 */
    data class Rendered(
        val text: String,
        val offsets: IntArray,
        val bullets: List<Pair<Int, Int>>
    )

    /** 没有列表项时返回 null，调用方直接使用原文即可。 */
    fun render(source: String): Rendered? {
        if (source.isEmpty()) return null
        val lines = source.split('\n')
        if (lines.none { listLine.matches(it) }) return null

        val builder = StringBuilder(source.length + 16)
        val offsets = ArrayList<Int>(source.length + 16)
        val bullets = mutableListOf<Pair<Int, Int>>()
        var sourceIndex = 0

        lines.forEachIndexed { index, line ->
            val match = listLine.matchEntire(line)
            if (match == null) {
                builder.append(line)
                repeat(line.length) { offsets.add(sourceIndex + it) }
            } else {
                val indent = match.groupValues[1]
                val depth = (indent.length / 2).coerceAtMost(2)
                builder.append(indent)
                repeat(indent.length) { offsets.add(sourceIndex + it) }

                // 圆点替代原来的列表符号，显示字符都映射回符号所在位置。
                // 列表整体再缩进一个字（全角空格正好一个汉字宽）。
                builder.append("\u3000")
                offsets.add(sourceIndex + indent.length)
                bullets.add(builder.length to depth)
                builder.append("• ")
                repeat(2) { offsets.add(sourceIndex + indent.length) }

                val body = match.groupValues[2]
                val bodyStart = sourceIndex + match.groups[2]!!.range.first
                builder.append(body)
                repeat(body.length) { offsets.add(bodyStart + it) }
            }
            sourceIndex += line.length
            if (index < lines.lastIndex) {
                builder.append('\n')
                offsets.add(sourceIndex)
                sourceIndex += 1
            }
        }
        return Rendered(builder.toString(), offsets.toIntArray(), bullets)
    }

    /** 把显示文本里的偏移换算成原文偏移。 */
    fun sourceOffset(rendered: Rendered?, displayOffset: Int): Int {
        val offsets = rendered?.offsets ?: return displayOffset
        if (offsets.isEmpty()) return displayOffset
        return offsets[displayOffset.coerceIn(0, offsets.lastIndex)]
    }

    /** 把列表符号画成大小随正文变化的灰色圆点，效果接近 Obsidian。 */
    fun style(spannable: Spannable, rendered: Rendered) {
        rendered.bullets.forEach { (start, depth) ->
            if (start >= spannable.length) return@forEach
            spannable.setSpan(
                DotSpan(depth),
                start,
                start + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private class DotSpan(private val depth: Int) : ReplacementSpan() {
        override fun getSize(
            paint: Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?
        ): Int {
            val size = paint.textSize
            return (size * if (depth == 0) 0.62f else 0.50f).toInt()
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint
        ) {
            val original = paint.color
            val size = paint.textSize
            val radius = size * if (depth == 0) 0.20f else 0.15f
            paint.color = BULLET_COLOR
            paint.isAntiAlias = true
            canvas.drawCircle(x + radius, (top + bottom) / 2f, radius, paint)
            paint.color = original
        }
    }
}
