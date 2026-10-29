package com.wu.diary

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.wu.diary.data.DiaryStore
import com.wu.diary.data.Settings
import com.wu.diary.databinding.ActivityHiddenDiaryBinding
import com.wu.diary.model.DiaryEntry
import com.wu.diary.ui.DiaryAdapter

/** 隐藏空间：集中展示所有已隐藏的笔记。 */
class HiddenDiaryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHiddenDiaryBinding
    private lateinit var store: DiaryStore
    private lateinit var settings: Settings
    private lateinit var adapter: DiaryAdapter

    private var entries: List<DiaryEntry> = emptyList()
    private var appliedScale = 1f
    private var appliedLines = 0
    private var appliedLineSpacing = 0f
    private var appliedShowTags = false
    private var appliedFontFamily = Settings.DEFAULT_FONT_FAMILY
    private var appliedLargeImages = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHiddenDiaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = DiaryStore(this)
        settings = Settings(this)
        appliedScale = settings.previewScale
        appliedLines = settings.previewLines
        appliedLineSpacing = settings.lineSpacing
        appliedShowTags = settings.previewTags
        appliedFontFamily = settings.fontFamily
        appliedLargeImages = settings.largeImages

        adapter = DiaryAdapter(
            typeface = settings.typeface,
            fontScale = settings.previewScale,
            previewLines = settings.previewLines,
            lineSpacing = settings.lineSpacing,
            showTags = settings.previewTags,
            largeImages = settings.largeImages,
            onClick = { entry ->
                val ids = entries.map { it.id }
                val index = entries.indexOfFirst { it.id == entry.id }.coerceAtLeast(0)
                startActivity(ViewDiaryActivity.intent(this, ids, index))
            },
            onLongClick = {}
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        if (appliedScale != settings.previewScale || appliedLines != settings.previewLines ||
            appliedLineSpacing != settings.lineSpacing || appliedShowTags != settings.previewTags ||
            appliedFontFamily != settings.fontFamily || appliedLargeImages != settings.largeImages
        ) {
            recreate()
            return
        }
        refresh()
    }

    private fun refresh() {
        entries = store.load().filter { it.hidden }
        adapter.submit(entries)
        binding.emptyView.isVisible = entries.isEmpty()
        binding.recycler.isVisible = entries.isNotEmpty()
    }
}
