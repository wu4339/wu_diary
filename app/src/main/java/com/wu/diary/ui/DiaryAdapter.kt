package com.wu.diary.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wu.diary.databinding.ItemDateBinding
import com.wu.diary.databinding.ItemDiaryBinding
import com.wu.diary.model.DiaryEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 按天分组的日记列表：同一天的记录共用一个日期头，记录之间用分割线隔开。 */
class DiaryAdapter(
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
        entries.sortedByDescending { it.updatedAt }.forEach { entry ->
            val date = Date(entry.updatedAt)
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
            is Row.DateHeader -> {
                val binding = (holder as DateViewHolder).binding
                binding.textDate.text = row.day
                binding.textYear.text = row.year
                val top = binding.root.resources.displayMetrics.density * if (row.isFirst) 16 else 34
                binding.root.setPadding(
                    binding.root.paddingLeft,
                    top.toInt(),
                    binding.root.paddingRight,
                    binding.root.paddingBottom
                )
            }

            is Row.Entry -> {
                val binding = (holder as EntryViewHolder).binding
                binding.textBody.text = listOf(row.entry.title.trim(), row.entry.content.trim())
                    .filter { it.isNotEmpty() }
                    .joinToString("\n")
                binding.root.setOnClickListener { onClick(row.entry) }
                binding.root.setOnLongClickListener { onLongClick(row.entry); true }
            }
        }
    }

    companion object {
        private const val TYPE_DATE = 0
        private const val TYPE_ENTRY = 1
    }
}
