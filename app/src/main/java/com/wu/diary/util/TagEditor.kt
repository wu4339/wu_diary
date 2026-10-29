package com.wu.diary.util

import android.app.Activity
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import com.wu.diary.R
import com.wu.diary.data.DiaryStore

/** 标签编辑对话框：可直接输入，也可以点选已有的标签；空格/逗号分隔，最多 10 个。 */
object TagEditor {

    fun show(activity: Activity, current: List<String>, onSaved: (List<String>) -> Unit) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val existingTags = DiaryStore(activity)
            .load()
            .flatMap { it.tags }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val input = EditText(activity).apply {
            setText(current.joinToString(" "))
            hint = activity.getString(R.string.tags_hint)
            setSingleLine(true)
            setPadding(dp(4), dp(10), dp(4), dp(10))
        }
        container.addView(input)

        if (existingTags.isNotEmpty()) {
            val chipViews = mutableListOf<Pair<String, TextView>>()

            fun refreshChips() {
                val selected = parseTags(input.text.toString())
                chipViews.forEach { (tag, view) ->
                    val isSelected = tag in selected
                    view.setTextColor(
                        ContextCompat.getColor(
                            activity,
                            if (isSelected) R.color.text_primary else R.color.text_secondary
                        )
                    )
                    view.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                }
            }

            // 现有标签按宽度自动换行，最多显示 3 行，更多时上下滑动选择。
            // 宽度铺满弹窗内容区，等布局完成后再按真实宽度排版，避免行尾留下空白。
            val rowHeight = dp(32)
            val chipArea = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val chipScroll = ScrollView(activity).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(chipArea)
            }
            container.addView(
                chipScroll,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    rowHeight * 3
                ).apply { topMargin = dp(10) }
            )

            fun layoutChips(width: Int) {
                if (width <= 0) return
                chipArea.removeAllViews()
                var row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                var used = 0
                chipViews.forEach { (_, chip) ->
                    (chip.parent as? ViewGroup)?.removeView(chip)
                    chip.measure(
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    )
                    val chipWidth = chip.measuredWidth + dp(4)
                    if (used > 0 && used + chipWidth > width) {
                        chipArea.addView(
                            row,
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                rowHeight
                            )
                        )
                        row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                        used = 0
                    }
                    row.addView(
                        chip,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = dp(4) }
                    )
                    used += chipWidth
                }
                if (row.childCount > 0) {
                    chipArea.addView(
                        row,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            rowHeight
                        )
                    )
                }
                chipScroll.layoutParams = chipScroll.layoutParams.apply {
                    height = rowHeight * minOf(3, chipArea.childCount)
                }
            }

            existingTags.forEach { tag ->
                val chip = TextView(activity).apply {
                    text = "#$tag"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(dp(6), dp(4), dp(6), dp(4))
                    isClickable = true
                    setOnClickListener {
                        val selected = parseTags(input.text.toString()).toMutableList()
                        if (tag in selected) selected.remove(tag) else selected.add(tag)
                        input.setText(selected.joinToString(" "))
                        input.setSelection(input.text.length)
                        refreshChips()
                    }
                }
                chipViews.add(tag to chip)
            }
            // 等弹窗内容完成布局后用真实宽度排版，行尾不会留下多余空白。
            chipScroll.doOnLayout { layoutChips(it.width) }
            refreshChips()
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.tag_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                // 添加标签与保存笔记使用同一个提示音。
                Feedback.saved(activity)
                onSaved(parseTags(input.text.toString()))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun parseTags(raw: String): List<String> =
        raw.split(Regex("[\\s,，、]+"))
            .map { it.trim().removePrefix("#") }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(10)
}
