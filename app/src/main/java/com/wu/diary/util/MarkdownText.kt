package com.wu.diary.util

/**
 * 正文里的 Markdown 无序列表（- / * / +）渲染成圆点列表，
 * 同时记录显示字符与原文字符的对应关系，双击定位时仍能落在正确的光标处。
 */
object MarkdownText {

    private val listLine = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val bullets = arrayOf("•  ", "◦  ", "▪  ")

    data class Rendered(val text: String, val offsets: IntArray)

    /** 没有列表项时返回 null，调用方直接使用原文即可。 */
    fun render(source: String): Rendered? {
        if (source.isEmpty()) return null
        val lines = source.split('\n')
        if (lines.none { listLine.matches(it) }) return null

        val builder = StringBuilder(source.length + 16)
        val offsets = ArrayList<Int>(source.length + 16)
        var sourceIndex = 0

        lines.forEachIndexed { index, line ->
            val match = listLine.matchEntire(line)
            if (match == null) {
                builder.append(line)
                repeat(line.length) { offsets.add(sourceIndex + it) }
            } else {
                val indent = match.groupValues[1]
                val depth = (indent.length / 2).coerceAtMost(bullets.size - 1)
                builder.append(indent)
                repeat(indent.length) { offsets.add(sourceIndex + it) }

                // 圆点替代原来的列表符号，显示字符都映射回符号所在位置。
                val bullet = bullets[depth]
                builder.append(bullet)
                repeat(bullet.length) { offsets.add(sourceIndex + indent.length) }

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
        return Rendered(builder.toString(), offsets.toIntArray())
    }

    /** 把显示文本里的偏移换算成原文偏移。 */
    fun sourceOffset(rendered: Rendered?, displayOffset: Int): Int {
        val offsets = rendered?.offsets ?: return displayOffset
        if (offsets.isEmpty()) return displayOffset
        return offsets[displayOffset.coerceIn(0, offsets.lastIndex)]
    }
}
