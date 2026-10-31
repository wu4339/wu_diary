package com.wu.diary

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wu.diary.data.DiaryStore
import com.wu.diary.databinding.ActivityTrashBinding
import com.wu.diary.databinding.ItemTrashBinding
import com.wu.diary.model.DiaryEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 回收站：列出被删除的笔记，可以恢复或彻底删除。 */
class TrashActivity : BaseActivity() {

    private lateinit var binding: ActivityTrashBinding
    private lateinit var store: DiaryStore

    private val entries = mutableListOf<DiaryEntry>()
    private val viewTimeFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
    private val trashAdapter = TrashAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = DiaryStore(this)
        binding.btnBack.setOnClickListener { finish() }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = trashAdapter
    }

    override fun onResume() {
        super.onResume()
        loadTrash()
    }

    private fun loadTrash() {
        val appContext = applicationContext
        Thread {
            val loaded = DiaryStore(appContext).loadTrash()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                entries.clear()
                entries.addAll(loaded)
                trashAdapter.notifyDataSetChanged()
                binding.emptyView.isVisible = loaded.isEmpty()
            }
        }.start()
    }

    private fun restore(entry: DiaryEntry) {
        Thread {
            store.restore(entry.id)
            runOnUiThread {
                Toast.makeText(this, R.string.trash_restored, Toast.LENGTH_SHORT).show()
                loadTrash()
            }
        }.start()
    }

    private fun purge(entry: DiaryEntry) {
        AlertDialog.Builder(this)
            .setTitle(R.string.trash_purge)
            .setMessage(R.string.trash_purge_confirm)
            .setPositiveButton(R.string.trash_purge) { _, _ ->
                Thread {
                    store.delete(entry.id)
                    runOnUiThread {
                        Toast.makeText(this, R.string.trash_purged, Toast.LENGTH_SHORT).show()
                        loadTrash()
                    }
                }.start()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class TrashAdapter : RecyclerView.Adapter<TrashAdapter.Holder>() {

        inner class Holder(val binding: ItemTrashBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemTrashBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = entries.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = entries.getOrNull(position) ?: return
            with(holder.binding) {
                val body = listOf(entry.title.trim(), entry.content.trim())
                    .filter { it.isNotEmpty() }
                    .joinToString("\n")
                textBody.text = body.ifEmpty { getString(R.string.trash_media_note) }
                textTime.text = viewTimeFormat.format(Date(entry.createdAt))
                btnRestore.setOnClickListener { restore(entry) }
                btnPurge.setOnClickListener { purge(entry) }
            }
        }
    }
}
