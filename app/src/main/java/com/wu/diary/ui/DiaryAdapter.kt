package com.wu.diary.ui

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.wu.diary.data.Settings
import com.wu.diary.databinding.ItemDateBinding
import com.wu.diary.databinding.ItemDiaryBinding
import com.wu.diary.model.DiaryEntry
import com.wu.diary.util.ImageLoader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 按天分组的日记列表：同一天的记录共用一个日期头，记录之间用分割线隔开。 */
class DiaryAdapter(
    private val fontScale: Float,
    private val previewLines: Int,
    private val onClick: (DiaryEntry) -> Unit,
    private val onLongClick: (DiaryEntry) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed interface Row {
        data class DateHeader(val day: String, val year: String, val isFirst: Boolean) : Row
        data class Entry(val entry: DiaryEntry) : Row
    }

    private val rows = mutableListOf<Row>()
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val dayTextFormat = SimpleDateFormat("M/d", Locale.getDefault())
    private val yearTextFormat = SimpleDateFormat("yyyy", Locale.getDefault())

    fun submit(entries: List<DiaryEntry>) {
        rows.clear()
        var currentDay: String? = null
        entries.sortedByDescending { it.createdAt }.forEach { entry ->
            val date = Date(entry.createdAt)
            val day = dayFormat.format(date)
            if (day != currentDay) {
                rows.add(Row.DateHeader(dayTextFormat.format(date), yearTextFormat.format(date), currentDay == null))
                currentDay = day
            }
            rows.add(Row.Entry(entry))
        }
        notifyDataSetChanged()
    }

    class DateViewHolder(val binding: ItemDateBinding) : RecyclerView.ViewHolder(binding.root)
    class EntryViewHolder(val binding: ItemDiaryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.DateHeader -> TYPE_DATE
        is Row.Entry -> TYPE_ENTRY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_DATE) {
            DateViewHolder(ItemDateBinding.inflate(inflater, parent, false))
        } else {
            EntryViewHolder(ItemDiaryBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.DateHeader -> bindDate((holder as DateViewHolder).binding, row)
            is Row.Entry -> bindEntry((holder as EntryViewHolder).binding, row.entry)
        }
    }

    private fun bindDate(binding: ItemDateBinding, row: Row.DateHeader) {
        binding.textDate.text = row.day
        binding.textYear.text = row.year
        binding.textDate.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f * fontScale)
        binding.textYear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * fontScale)
        val top = binding.root.resources.displayMetrics.density * if (row.isFirst) 16 else 34
        binding.root.setPadding(
            binding.root.paddingLeft,
            top.toInt(),
            binding.root.paddingRight,
            binding.root.paddingBottom
        )
    }

    private fun bindEntry(binding: ItemDiaryBinding, entry: DiaryEntry) {
        val body = listOf(entry.title.trim(), entry.content.trim())
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        binding.textBody.text = body
        binding.textBody.isVisible = body.isNotEmpty()
        binding.textBody.maxLines =
            if (previewLines == Settings.PREVIEW_ALL) Int.MAX_VALUE else previewLines
        binding.textBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * fontScale)

        binding.imageStrip.removeAllViews()
        binding.imageStrip.isVisible = entry.images.isNotEmpty()
        val thumb = (78 * binding.root.resources.displayMetrics.density).toInt()
        val gap = (8 * binding.root.resources.displayMetrics.density).toInt()
        entry.images.take(3).forEach { path ->
            val image = ImageView(binding.root.context).apply {
                layoutParams = LinearLayout.LayoutParams(thumb, thumb).apply { rightMargin = gap }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(ImageLoader.load(path, thumb))
            }
            binding.imageStrip.addView(image)
        }

        binding.root.setOnClickListener { onClick(entry) }
        binding.root.setOnLongClickListener { onLongClick(entry); true }
    }

    companion object {
        private const val TYPE_DATE = 0
        private const val TYPE_ENTRY = 1
    }
}
