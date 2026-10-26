package com.wu.diary

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.wu.diary.data.DiaryStore
import com.wu.diary.data.Settings
import com.wu.diary.databinding.ActivitySearchBinding
import com.wu.diary.model.DiaryEntry
import com.wu.diary.ui.DiaryAdapter

/** 独立的搜索界面：输入关键词过滤笔记，命中内容高亮，点击进入浏览页。 */
class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private lateinit var adapter: DiaryAdapter
    private lateinit var store: DiaryStore

    private var allEntries: List<DiaryEntry> = emptyList()
    private var results: List<DiaryEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = DiaryStore(this)
        val settings = Settings(this)
        allEntries = store.load().filter { !it.hidden }

        adapter = DiaryAdapter(
            fontScale = settings.fontScale,
            previewLines = settings.previewLines,
            lineSpacing = settings.lineSpacing,
            onClick = { entry ->
                val ids = results.map { it.id }
                val index = results.indexOfFirst { it.id == entry.id }.coerceAtLeast(0)
                startActivity(ViewDiaryActivity.intent(this, ids, index))
            },
            onLongClick = {}
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.editSearch.doAfterTextChanged { text -> showResults(text?.toString().orEmpty()) }

        binding.editSearch.requestFocus()
        showResults("")
    }

    /** 从浏览页返回时笔记可能被编辑或删除，重新读取并保留当前关键词。 */
    override fun onResume() {
        super.onResume()
        allEntries = store.load().filter { !it.hidden }
        showResults(binding.editSearch.text.toString())
    }

    private fun showResults(query: String) {
        val keyword = query.trim()
        results = if (keyword.isEmpty()) {
            allEntries
        } else {
            allEntries.filter { entry ->
                entry.content.contains(keyword, ignoreCase = true) ||
                    entry.title.contains(keyword, ignoreCase = true) ||
                    entry.tags.any { it.contains(keyword, ignoreCase = true) }
            }
        }
        adapter.submit(results, keyword)
        binding.emptyView.isVisible = results.isEmpty()
        binding.recycler.isVisible = results.isNotEmpty()
    }
}
