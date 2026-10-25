package com.wu.diary.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wu.diary.R
import com.wu.diary.databinding.ItemDiaryBinding
import com.wu.diary.model.DiaryEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiaryAdapter(
    private val onClick: (DiaryEntry) -> Unit,
    private val onLongClick: (DiaryEntry) -> Unit
) : RecyclerView.Adapter<DiaryAdapter.ViewHolder>() {

    private val items = mutableListOf<DiaryEntry>()

    fun submit(entries: List<DiaryEntry>) {
        items.clear()
        items.addAll(entries)
        notifyDataSetChanged()
    }

    class ViewHolder(val binding: ItemDiaryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDiaryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = items[position]
        with(holder.binding) {
            textTitle.text = entry.title.ifBlank { root.context.getString(R.string.untitled) }
            textDay.text = SimpleDateFormat("d", Locale.getDefault()).format(Date(entry.updatedAt))
            textMonth.text = SimpleDateFormat("M月", Locale.CHINA).format(Date(entry.updatedAt))
            textTime.text = SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA).format(Date(entry.updatedAt))
            textContent.text = entry.content
            root.setOnClickListener { onClick(entry) }
            root.setOnLongClickListener { onLongClick(entry); true }
        }
    }
}
