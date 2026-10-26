package com.wu.diary

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.wu.diary.data.DiaryStore
import com.wu.diary.data.Settings
import com.wu.diary.databinding.ActivityMainBinding
import com.wu.diary.ui.DiaryAdapter

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: DiaryStore
    private lateinit var settings: Settings
    private lateinit var adapter: DiaryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = DiaryStore(this)
        settings = Settings(this)
        adapter = DiaryAdapter(
            fontScale = settings.fontScale,
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
        binding.btnMenu.setOnClickListener { showFontSizeDialog() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val entries = store.load()
        adapter.submit(entries)
        binding.emptyView.isVisible = entries.isEmpty()
        binding.recycler.isVisible = entries.isNotEmpty()
    }

    private fun showFontSizeDialog() {
        val labels = arrayOf(
            getString(R.string.font_small),
            getString(R.string.font_default),
            getString(R.string.font_large),
            getString(R.string.font_huge)
        )
        val scales = Settings.FONT_SCALES
        val current = scales.indexOfFirst { it == settings.fontScale }.coerceAtLeast(1)
        AlertDialog.Builder(this)
            .setTitle(R.string.font_size)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                settings.fontScale = scales[which]
                recreate()
            }
            .show()
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
