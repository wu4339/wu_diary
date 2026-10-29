package com.wu.diary

import android.content.res.ColorStateList
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
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

    private var hasImageFilter = false
    private var hasLocationFilter = false
    private var hasTagFilter = false
    private var noTagFilter = false
    private var hasLinkFilter = false

    /** 笔记里插入的坐标形如“31.230416, 121.473701”。 */
    private val locationPattern = Regex("-?\\d{1,3}\\.\\d{4,}\\s*,\\s*-?\\d{1,3}\\.\\d{4,}")

    /** 正文里的网址：http(s):// 或 www. 开头（Markdown 链接同样包含这类地址）。 */
    private val linkPattern = Regex("(?i)(https?://|www\\.)")

    private val filterLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data ?: return@registerForActivityResult
            hasImageFilter = data.getBooleanExtra(SearchFilterActivity.EXTRA_HAS_IMAGE, false)
            hasLocationFilter = data.getBooleanExtra(SearchFilterActivity.EXTRA_HAS_LOCATION, false)
            hasTagFilter = data.getBooleanExtra(SearchFilterActivity.EXTRA_HAS_TAG, false)
            noTagFilter = data.getBooleanExtra(SearchFilterActivity.EXTRA_NO_TAG, false)
            hasLinkFilter = data.getBooleanExtra(SearchFilterActivity.EXTRA_HAS_LINK, false)
            updateFilterIcon()
            showResults(binding.editSearch.text.toString())
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = DiaryStore(this)
        val settings = Settings(this)
        allEntries = store.load().filter { !it.hidden }

        adapter = DiaryAdapter(
            typeface = settings.typeface,
            fontScale = settings.previewScale,
            previewLines = settings.previewLines,
            lineSpacing = settings.lineSpacing,
            showTags = settings.previewTags,
            largeImages = settings.largeImages,
            mediumImages = settings.mediumImages,
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
        binding.btnFilter.setOnClickListener {
            filterLauncher.launch(
                SearchFilterActivity.intent(
                    this,
                    hasImageFilter,
                    hasLocationFilter,
                    hasTagFilter,
                    noTagFilter,
                    hasLinkFilter
                )
            )
        }

        updateFilterIcon()
        binding.editSearch.requestFocus()
        showResults("")
    }

    /** 从浏览页返回时笔记可能被编辑或删除，重新读取并保留当前关键词。 */
    override fun onResume() {
        super.onResume()
        allEntries = store.load().filter { !it.hidden }
        showResults(binding.editSearch.text.toString())
    }

    /** 有筛选条件时图标加深，提示当前已启用高级搜索。 */
    private fun updateFilterIcon() {
        val active = hasImageFilter || hasLocationFilter || hasTagFilter || noTagFilter ||
            hasLinkFilter
        ImageViewCompat.setImageTintList(
            binding.btnFilter,
            ColorStateList.valueOf(
                ContextCompat.getColor(
                    this,
                    if (active) R.color.text_primary else R.color.text_secondary
                )
            )
        )
    }

    private fun matchFilters(entry: DiaryEntry): Boolean {
        if (hasImageFilter && entry.images.isEmpty()) return false
        if (hasLocationFilter && !locationPattern.containsMatchIn(entry.content)) return false
        if (hasTagFilter && entry.tags.isEmpty()) return false
        if (noTagFilter && entry.tags.isNotEmpty()) return false
        if (hasLinkFilter && !linkPattern.containsMatchIn(entry.content)) return false
        return true
    }

    private fun showResults(query: String) {
        val keyword = query.trim()
        val filterActive = hasImageFilter || hasLocationFilter || hasTagFilter || noTagFilter ||
            hasLinkFilter
        // 既没有关键词也没有筛选条件时保持空白，不预先列出全部笔记。
        val blank = keyword.isEmpty() && !filterActive
        results = if (blank) {
            emptyList()
        } else {
            allEntries.filter { entry ->
                matchFilters(entry) && (keyword.isEmpty() ||
                    entry.content.contains(keyword, ignoreCase = true) ||
                    entry.title.contains(keyword, ignoreCase = true) ||
                    entry.tags.any { it.contains(keyword, ignoreCase = true) })
            }
        }
        adapter.submit(results, keyword)
        binding.emptyView.isVisible = !blank && results.isEmpty()
        binding.recycler.isVisible = results.isNotEmpty()
    }
}
