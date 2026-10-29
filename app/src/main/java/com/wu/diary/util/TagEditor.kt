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
            container.addView(
                TextView(activity).apply {
                    text = activity.getString(R.string.existing_tags)
                    setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, dp(16), 0, dp(6))
                }
            )
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
            // 弹窗的自定义视图宽度在展示前是 0，这里按屏幕宽度估算内容宽度，保证标签一定能显示。
            val rowHeight = dp(40)
            val contentWidth =
                (minOf(activity.resources.displayMetrics.widthPixels, dp(560)) - dp(88))
                    .coerceAtLeast(dp(160))
            val chipArea = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val chipScroll = ScrollView(activity).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(chipArea)
            }
            container.addView(
                chipScroll,
                LinearLayout.LayoutParams(contentWidth, rowHeight * 3)
            )

            fun layoutChips(width: Int) {
                if (width <= 0 || chipArea.childCount > 0) return
                var row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                var used = 0
                chipViews.forEach { (_, chip) ->
                    (chip.parent as? ViewGroup)?.removeView(chip)
                    chip.measure(
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    )
                    val chipWidth = chip.measuredWidth + dp(8)
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
                        ).apply { marginEnd = dp(8) }
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
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setPadding(dp(10), dp(8), dp(10), dp(8))
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
            layoutChips(contentWidth)
            refreshChips()
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.tag_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
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
