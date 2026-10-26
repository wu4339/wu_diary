package com.wu.diary

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.wu.diary.data.DiaryStore
import com.wu.diary.data.Settings
import com.wu.diary.databinding.ActivityMainBinding
import com.wu.diary.model.DiaryEntry
import com.wu.diary.ui.DiaryAdapter
import com.wu.diary.util.Feedback
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: DiaryStore
    private lateinit var settings: Settings
    private lateinit var adapter: DiaryAdapter

    private var calendarVisible = false
    private var displayYear = 0
    private var displayMonth = 0
    private var selectedYear = 0
    private var selectedMonth = 0
    private var selectedDay = 0
    private var allEntries: List<DiaryEntry> = emptyList()
    private var displayedEntries: List<DiaryEntry> = emptyList()
    private var appliedScale = 1f
    private var appliedLines = 0
    private var searchQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = DiaryStore(this)
        settings = Settings(this)
        appliedScale = settings.fontScale
        appliedLines = settings.previewLines

        val today = Calendar.getInstance()
        displayYear = today.get(Calendar.YEAR)
        displayMonth = today.get(Calendar.MONTH)
        selectedYear = displayYear
        selectedMonth = displayMonth
        selectedDay = today.get(Calendar.DAY_OF_MONTH)

        adapter = DiaryAdapter(
            fontScale = settings.fontScale,
            previewLines = settings.previewLines,
            onClick = { entry ->
                val ids = displayedEntries.map { it.id }
                val index = displayedEntries.indexOfFirst { it.id == entry.id }.coerceAtLeast(0)
                startActivity(ViewDiaryActivity.intent(this, ids, index))
            },
            onLongClick = { entry -> confirmDelete(entry) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        binding.fabAdd.setOnClickListener {
            val date = if (calendarVisible) selectedDateMillis() else null
            startActivity(EditDiaryActivity.intent(this, null, date))
        }
        binding.btnMenu.setOnClickListener {
            binding.drawerRoot.openDrawer(binding.drawerPanel)
        }
        binding.itemSettings.setOnClickListener {
            binding.drawerRoot.closeDrawer(binding.drawerPanel)
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.itemRandomReview.setOnClickListener {
            binding.drawerRoot.closeDrawer(binding.drawerPanel)
            reviewRandom()
        }
        binding.fabWallet.setOnClickListener { toggleCalendar() }
        binding.btnPrevMonth.setOnClickListener { shiftMonth(-1) }
        binding.btnNextMonth.setOnClickListener { shiftMonth(1) }

        binding.btnSearch.setOnClickListener { enterSearch() }
        binding.btnSearchClose.setOnClickListener { exitSearch() }
        binding.editSearch.doAfterTextChanged { text ->
            searchQuery = text?.toString()?.trim().orEmpty()
            applyList()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.searchBar.isVisible) {
                    exitSearch()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        applyScaledSizes()
        renderCalendar()
    }

    override fun onResume() {
        super.onResume()
        if (appliedScale != settings.fontScale || appliedLines != settings.previewLines) {
            recreate()
            return
        }
        refresh()
    }

    private fun refresh() {
        allEntries = store.load()
        if (calendarVisible) renderCalendar()
        applyList()
    }

    private fun applyList() {
        val entries = when {
            searchQuery.isNotEmpty() -> allEntries.filter { entry ->
                entry.content.contains(searchQuery, ignoreCase = true) ||
                    entry.title.contains(searchQuery, ignoreCase = true)
            }
            calendarVisible -> allEntries.filter { entry ->
                val day = Calendar.getInstance().apply { timeInMillis = entry.createdAt }
                day.get(Calendar.YEAR) == selectedYear &&
                    day.get(Calendar.MONTH) == selectedMonth &&
                    day.get(Calendar.DAY_OF_MONTH) == selectedDay
            }
            else -> allEntries
        }
        adapter.submit(entries)
        displayedEntries = entries
        binding.emptyView.isVisible = entries.isEmpty()
        binding.recycler.isVisible = entries.isNotEmpty()
        binding.emptySubtitle.text = getString(
            if (searchQuery.isNotEmpty()) R.string.search_empty else R.string.empty_subtitle
        )
    }

    private fun enterSearch() {
        if (calendarVisible) toggleCalendar()
        binding.drawerRoot.closeDrawer(binding.drawerPanel)
        binding.searchBar.isVisible = true
        binding.btnMenu.isVisible = false
        binding.btnSearch.isVisible = false
        binding.editSearch.setText("")
        binding.editSearch.requestFocus()
        binding.editSearch.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.editSearch, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun exitSearch() {
        binding.searchBar.isVisible = false
        binding.btnMenu.isVisible = true
        binding.btnSearch.isVisible = true
        binding.editSearch.setText("")
        searchQuery = ""
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.editSearch.windowToken, 0)
        binding.editSearch.clearFocus()
        applyList()
    }

    private fun toggleCalendar() {
        calendarVisible = !calendarVisible
        binding.calendarPanel.isVisible = calendarVisible
        binding.fabWallet.setImageResource(
            if (calendarVisible) R.drawable.ic_stack else R.drawable.ic_wallet
        )
        if (calendarVisible) {
            val today = Calendar.getInstance()
            displayYear = today.get(Calendar.YEAR)
            displayMonth = today.get(Calendar.MONTH)
            selectedYear = displayYear
            selectedMonth = displayMonth
            selectedDay = today.get(Calendar.DAY_OF_MONTH)
            renderCalendar()
        }
        applyList()
    }

    private fun shiftMonth(delta: Int) {
        val calendar = Calendar.getInstance().apply {
            clear()
            set(displayYear, displayMonth, 1)
        }
        calendar.add(Calendar.MONTH, delta)
        displayYear = calendar.get(Calendar.YEAR)
        displayMonth = calendar.get(Calendar.MONTH)
        renderCalendar()
    }

    private fun renderCalendar() {
        binding.textCalMonth.text = (displayMonth + 1).toString()
        binding.textCalYear.text = displayYear.toString()

        val entryDays = HashSet<String>()
        allEntries.forEach { entry ->
            val day = Calendar.getInstance().apply { timeInMillis = entry.createdAt }
            entryDays.add(
                dayKey(
                    day.get(Calendar.YEAR),
                    day.get(Calendar.MONTH),
                    day.get(Calendar.DAY_OF_MONTH)
                )
            )
        }

        val first = Calendar.getInstance().apply {
            clear()
            set(displayYear, displayMonth, 1)
        }
        val leading = first.get(Calendar.DAY_OF_WEEK) - 1
        val daysInMonth = first.getActualMaximum(Calendar.DAY_OF_MONTH)
        val rows = (leading + daysInMonth + 6) / 7

        val cursor = Calendar.getInstance().apply {
            clear()
            set(displayYear, displayMonth, 1)
            add(Calendar.DAY_OF_MONTH, -leading)
        }

        val density = resources.displayMetrics.density
        binding.dayGrid.removeAllViews()
        repeat(rows) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            binding.dayGrid.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (44 * density).toInt())
            )
            repeat(7) {
                val year = cursor.get(Calendar.YEAR)
                val month = cursor.get(Calendar.MONTH)
                val day = cursor.get(Calendar.DAY_OF_MONTH)
                row.addView(buildDayCell(year, month, day, entryDays.contains(dayKey(year, month, day))))
                cursor.add(Calendar.DAY_OF_MONTH, 1)
            }
        }
    }

    private fun buildDayCell(year: Int, month: Int, dayOfMonth: Int, hasEntry: Boolean): View {
        val density = resources.displayMetrics.density
        val inMonth = month == displayMonth
        val isSelected = year == selectedYear && month == selectedMonth && dayOfMonth == selectedDay

        val cell = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            if (isSelected) setBackgroundResource(R.drawable.bg_day_selected)
        }
        val size = (44 * density).toInt()
        cell.addView(inner, FrameLayout.LayoutParams(size, size, Gravity.CENTER))

        val number = TextView(this).apply {
            text = dayOfMonth.toString()
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (inMonth) R.color.text_primary else R.color.text_secondary
                )
            )
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * settings.fontScale)
        }
        inner.addView(number)

        val dot = View(this).apply {
            setBackgroundResource(R.drawable.bg_day_dot)
            visibility = if (hasEntry) View.VISIBLE else View.INVISIBLE
        }
        inner.addView(
            dot,
            LinearLayout.LayoutParams((5 * density).toInt(), (5 * density).toInt()).apply {
                topMargin = (3 * density).toInt()
            }
        )

        cell.setOnClickListener {
            selectedYear = year
            selectedMonth = month
            selectedDay = dayOfMonth
            if (!inMonth) {
                displayYear = year
                displayMonth = month
            }
            renderCalendar()
            applyList()
        }
        return cell
    }

    private fun applyScaledSizes() {
        val scale = settings.fontScale
        binding.textCalMonth.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f * scale)
        binding.textCalYear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * scale)
        binding.editSearch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f * scale)
        val header = binding.weekHeader
        for (index in 0 until header.childCount) {
            (header.getChildAt(index) as? TextView)
                ?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * scale)
        }
    }

    private fun dayKey(year: Int, month: Int, day: Int) = "$year-$month-$day"

    /** 日历模式下新建日记时使用的日期：选中那天 + 当前时分秒。 */
    private fun selectedDateMillis(): Long {
        val now = Calendar.getInstance()
        return Calendar.getInstance().apply {
            clear()
            set(
                selectedYear,
                selectedMonth,
                selectedDay,
                now.get(Calendar.HOUR_OF_DAY),
                now.get(Calendar.MINUTE),
                now.get(Calendar.SECOND)
            )
        }.timeInMillis
    }

    /** 随机挑一篇已有日记打开浏览页。 */
    private fun reviewRandom() {
        val entries = store.load()
        if (entries.isEmpty()) {
            Toast.makeText(this, R.string.random_review_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val entry = entries.random()
        startActivity(ViewDiaryActivity.intent(this, listOf(entry.id), 0))
    }

    private fun confirmDelete(entry: DiaryEntry) {
        AlertDialog.Builder(this)
            .setMessage(R.string.delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                store.delete(entry.id)
                Feedback.deleted(this)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
