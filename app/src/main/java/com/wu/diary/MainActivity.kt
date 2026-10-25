package com.wu.diary

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.wu.diary.data.DiaryStore
import com.wu.diary.databinding.ActivityMainBinding
import com.wu.diary.ui.DiaryAdapter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: DiaryStore
    private lateinit var adapter: DiaryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        bindHeader()

        store = DiaryStore(this)
        adapter = DiaryAdapter(
            onClick = { entry ->
                startActivity(EditDiaryActivity.intent(this, entry.id))
            },
            onLongClick = { entry -> confirmDelete(entry) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        binding.fabAdd.setOnClickListener {
            startActivity(EditDiaryActivity.intent(this, null))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun bindHeader() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        binding.textGreeting.setText(
            when (hour) {
                in 5..11 -> R.string.greeting_morning
                in 12..17 -> R.string.greeting_afternoon
                else -> R.string.greeting_evening
            }
        )
        binding.textToday.text = SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(Date())
    }

    private fun refresh() {
        val entries = store.load()
        adapter.submit(entries)
        binding.emptyView.isVisible = entries.isEmpty()
        binding.recycler.isVisible = entries.isNotEmpty()
    }

    private fun confirmDelete(entry: com.wu.diary.model.DiaryEntry) {
        AlertDialog.Builder(this)
            .setMessage(R.string.delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                store.delete(entry.id)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
