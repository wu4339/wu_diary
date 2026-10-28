package com.wu.diary

import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.appbar.AppBarLayout
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
    private var appliedLineSpacing = 0f
    private var appliedShowTags = false
    private var appliedFontFamily = Settings.DEFAULT_FONT_FAMILY
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    /** 本次手势已经用于滑出侧栏，后续事件不再交给列表。 */
    private var drawerSwiped = false
    private var pullStartX = 0f
    private var pullStartY = 0f
    private var pullDistance = 0f
    private var pullActive = false
    private var pullReady = false
    private var pullAllowed = false
    private var pullTriggered = false
    private var receiverRegistered = false
    /** 当前只显示某个标签的笔记；为空表示不过滤。 */
    private var activeTag: String? = null

    /** 后台保存完成后通知刷新，避免刚编辑完回到列表时读到写入中的文件。 */
    private val dataChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refresh()
        }
    }
    private var pullAnimator: ValueAnimator? = null

    /** 上次随机回顾到的笔记，避免连续两次打开同一篇。 */
    private var lastRandomId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = DiaryStore(this)
        settings = Settings(this)
        appliedScale = settings.previewScale
        appliedLines = settings.previewLines
        appliedLineSpacing = settings.lineSpacing
        appliedShowTags = settings.previewTags
        appliedFontFamily = settings.fontFamily

        val today = Calendar.getInstance()
        displayYear = today.get(Calendar.YEAR)
        displayMonth = today.get(Calendar.MONTH)
        selectedYear = displayYear
        selectedMonth = displayMonth
        selectedDay = today.get(Calendar.DAY_OF_MONTH)

        adapter = DiaryAdapter(
            typeface = settings.typeface,
            fontScale = settings.previewScale,
            previewLines = settings.previewLines,
            lineSpacing = settings.lineSpacing,
            showTags = settings.previewTags,
            onClick = { entry ->
                val ids = displayedEntries.map { it.id }
                val index = displayedEntries.indexOfFirst { it.id == entry.id }.coerceAtLeast(0)
                startActivity(ViewDiaryActivity.intent(this, ids, index))
            },
            onLongClick = { entry -> confirmDelete(entry) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        // 新增笔记：淡入 + 其他笔记下移；变化项不做交叉淡入淡出，避免无关笔记闪一下。
        // 删除笔记使用自定义的纯视觉动画，不经过列表动画器。
        defaultItemAnimator = (binding.recycler.itemAnimator as? DefaultItemAnimator)?.apply {
            addDuration = 220
            moveDuration = 260
            changeDuration = 0
            supportsChangeAnimations = false
        } ?: DefaultItemAnimator()
        binding.fabAdd.setOnClickListener {
            val date = if (calendarVisible) selectedDateMillis() else null
            startActivity(EditDiaryActivity.intent(this, null, date))
            // 编辑页像从列表里展开：主界面原位不动，编辑页放大进入。
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.editor_grow_in, 0)
        }
        binding.btnMenu.setOnClickListener {
            binding.drawerRoot.openDrawer(binding.drawerPanel)
        }
        binding.btnSettingsTop.setOnClickListener {
            binding.drawerRoot.closeDrawer(binding.drawerPanel)
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.itemRandomReview.setOnClickListener {
            binding.drawerRoot.closeDrawer(binding.drawerPanel)
            reviewRandom()
        }
        binding.itemHiddenSpace.setOnClickListener {
            binding.drawerRoot.closeDrawer(binding.drawerPanel)
            startActivity(Intent(this, HiddenDiaryActivity::class.java))
        }
        binding.fabWallet.setOnClickListener { toggleCalendar() }
        binding.btnPrevMonth.setOnClickListener { shiftMonth(-1) }
        binding.btnNextMonth.setOnClickListener { shiftMonth(1) }

        // 顶栏与日历收起时，底部悬浮按键一起下移淡出。
        binding.appBar.addOnOffsetChangedListener(
            AppBarLayout.OnOffsetChangedListener { _, verticalOffset ->
                val total = binding.appBar.totalScrollRange.toFloat()
                val fraction = if (total <= 0f) 0f else (-verticalOffset / total).coerceIn(0f, 1f)
                binding.fabBar.alpha = 1f - fraction
                binding.fabBar.translationY = binding.fabBar.height * fraction
            }
        )

        binding.btnSearch.setOnClickListener {
            binding.drawerRoot.closeDrawer(binding.drawerPanel)
            startActivity(Intent(this, SearchActivity::class.java))
        }

        binding.tagFilterBar.setOnClickListener {
            activeTag = null
            renderTags()
            applyList()
            Toast.makeText(this, R.string.tag_filter_cleared, Toast.LENGTH_SHORT).show()
        }

        applyScaledSizes()
        renderCalendar()

        // 从创建到销毁一直监听数据变更：后台写入完成时即使界面处于后台也能收到广播并刷新。
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                dataChangedReceiver,
                IntentFilter(DiaryStore.ACTION_DATA_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (receiverRegistered) {
            unregisterReceiver(dataChangedReceiver)
            receiverRegistered = false
        }
    }

    /** 主界面任意位置向右滑动即可拉出左侧栏（不只限于屏幕边缘）；顶部下拉读取剪贴板。 */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val density = resources.displayMetrics.density
        val drawerThreshold = 80 * density
        val pullThreshold = 72 * density
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeDownX = ev.x
                swipeDownY = ev.y
                drawerSwiped = false
                pullStartX = ev.x
                pullStartY = ev.y
                pullDistance = 0f
                pullActive = false
                pullTriggered = false
                pullAllowed = !calendarVisible &&
                    !binding.drawerRoot.isDrawerOpen(binding.drawerPanel) &&
                    !binding.recycler.canScrollVertically(-1)
            }
            MotionEvent.ACTION_MOVE -> {
                // 已经滑出侧栏的这一次手势不再传给列表，避免松手时误触打开笔记。
                if (drawerSwiped) return true
                val dx = ev.x - swipeDownX
                val dy = kotlin.math.abs(ev.y - swipeDownY)
                if (dx > drawerThreshold && dx > dy * 1.5f) {
                    if (!binding.drawerRoot.isDrawerOpen(binding.drawerPanel)) {
                        binding.drawerRoot.openDrawer(binding.drawerPanel)
                        swipeDownX = ev.x
                        drawerSwiped = true
                        cancelChildTouch(ev)
                        return true
                    }
                }
                // 向左滑：直接切到随机回顾。
                if (dx < -drawerThreshold && -dx > dy * 1.5f && !calendarVisible &&
                    !binding.drawerRoot.isDrawerOpen(binding.drawerPanel)
                ) {
                    drawerSwiped = true
                    cancelChildTouch(ev)
                    reviewRandom()
                    return true
                }
                if (pullAllowed && !pullTriggered) {
                    val pullDy = ev.y - pullStartY
                    val pullDx = kotlin.math.abs(ev.x - pullStartX)
                    if (pullDy > 0 && pullDy > pullDx * 1.5f) {
                        pullActive = true
                        pullDistance = pullDy
                        updatePullHint(pullDistance, pullThreshold)
                    } else if (pullDy < 0 && pullActive) {
                        pullActive = false
                        pullDistance = 0f
                        collapsePull()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drawerSwiped) {
                    drawerSwiped = false
                    return true
                }
                if (pullActive) {
                    val ready = ev.actionMasked == MotionEvent.ACTION_UP &&
                        pullDistance >= pullThreshold
                    pullActive = false
                    pullDistance = 0f
                    collapsePull()
                    if (ready) {
                        pullTriggered = true
                        checkClipboard()
                        return true
                    }
                }
                // 上滑收起顶栏与日历，下滑重新展开（列表本身能滚动时也会自然触发）。
                val dy = ev.y - swipeDownY
                val dx = ev.x - swipeDownX
                if (kotlin.math.abs(dy) > 56 * density && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.5f) {
                    binding.appBar.setExpanded(dy > 0, true)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** 滑出侧栏时给列表补发取消事件，避免列表项把这串触摸当成点击。 */
    private fun cancelChildTouch(ev: MotionEvent) {
        val cancel = MotionEvent.obtain(ev)
        cancel.action = MotionEvent.ACTION_CANCEL
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    /** 下拉过程中实时展示提示条高度与文案。 */
    private fun updatePullHint(distance: Float, threshold: Float) {
        pullAnimator?.cancel()
        val density = resources.displayMetrics.density
        val height = distance.coerceIn(0f, 88 * density).toInt()
        val params = binding.pullHint.layoutParams
        if (params.height != height) {
            params.height = height
            binding.pullHint.layoutParams = params
        }
        val ready = distance >= threshold
        if (ready != pullReady) {
            pullReady = ready
            binding.pullText.setText(if (ready) R.string.pull_release else R.string.pull_hint)
            binding.pullIcon.rotation = if (ready) 180f else 0f
        }
    }

    /** 松手后提示条回收。 */
    private fun collapsePull() {
        pullReady = false
        binding.pullText.setText(R.string.pull_hint)
        binding.pullIcon.rotation = 0f
        val params = binding.pullHint.layoutParams
        val from = params.height
        if (from <= 0) return
        pullAnimator?.cancel()
        pullAnimator = ValueAnimator.ofInt(from, 0).apply {
            duration = 150
            addUpdateListener {
                params.height = it.animatedValue as Int
                binding.pullHint.layoutParams = params
            }
            start()
        }
    }

    /** 顶部下拉：剪贴板有文字时，询问是否用它新建一篇笔记。 */
    private fun checkClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this).toString().trim()
        } else {
            ""
        }
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val preview = if (text.length > 120) text.take(120) + "…" else text
        AlertDialog.Builder(this)
            .setTitle(R.string.clipboard_title)
            .setMessage(preview)
            .setPositiveButton(R.string.clipboard_create) { _, _ ->
                val now = System.currentTimeMillis()
                store.save(
                    DiaryEntry(title = "", content = text, createdAt = now, updatedAt = now)
                )
                Feedback.saved(this)
                refresh()
                Toast.makeText(this, R.string.clipboard_created, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (appliedScale != settings.previewScale || appliedLines != settings.previewLines ||
            appliedLineSpacing != settings.lineSpacing || appliedShowTags != settings.previewTags ||
            appliedFontFamily != settings.fontFamily
        ) {
            recreate()
            return
        }
        refresh()
        // 重启应用（冷启动首次进入）显示最新日期：等首次布局完成后回到列表顶部并展开顶栏；
        // 后台恢复或从应用内页面返回时保持原来的浏览位置。
        if (firstResume) {
            firstResume = false
            binding.recycler.post {
                binding.recycler.stopScroll()
                (binding.recycler.layoutManager as? LinearLayoutManager)?.scrollToPosition(0)
                binding.appBar.setExpanded(true, false)
            }
        }
    }

    /** 等待播放新增笔记动画期间不再响应其他刷新，避免动画被打断造成闪烁。 */
    private var pendingRevealId: String? = null

    /** 重启应用后首次进入界面时回到顶部。 */
    private var firstResume = true

    /** 常规条目动画器；删除时临时移除，避免布局更新时再叠加一层动画。 */
    private var defaultItemAnimator: DefaultItemAnimator? = null

    /** 删除动画结束时需要补回的滚动偏移，保证删除后上方内容不下移。 */
    private var pendingRemovalScrollOffset: Int? = null

    private fun refresh() {
        if (pendingRevealId != null) return
        val previousIds = allEntries.map { it.id }.toSet()
        allEntries = store.load().filter { !it.hidden }
        val addedId = allEntries.firstOrNull { it.id !in previousIds }?.id
        binding.textNoteCount.text = getString(R.string.note_count, allEntries.size)
        renderTags()
        if (calendarVisible) renderCalendar()

        val lm = binding.recycler.layoutManager as? LinearLayoutManager
        if (addedId != null && lm != null && lm.findFirstVisibleItemPosition() > 0) {
            pendingRevealId = addedId
            // 先等编辑页缩小动画（约220ms）播完，再分两步：
            // 1) 停止惯性滚动，回到顶部（只含旧列表）；2) 列表稳定后再插入新笔记。
            // 滚动与插入不放在同一次布局里，否则新条目可能不被挂载，要滑动一下才出现。
            binding.recycler.postDelayed({
                applyList(excludeId = addedId)
                binding.recycler.post {
                    binding.recycler.stopScroll()
                    (binding.recycler.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(0, 0)
                }
                binding.recycler.postDelayed({
                    pendingRevealId = null
                    applyList()
                    // 往旧日期添加时插入点可能在屏幕外，等布局完成后再定位过去。
                    binding.recycler.post {
                        val pos = adapter.positionOf(addedId)
                        val last = (binding.recycler.layoutManager as? LinearLayoutManager)
                            ?.findLastVisibleItemPosition() ?: -1
                        if (pos >= 0 && last >= 0 && pos > last) {
                            binding.recycler.stopScroll()
                            (binding.recycler.layoutManager as? LinearLayoutManager)
                                ?.scrollToPositionWithOffset(pos, 0)
                        }
                    }
                }, 350)
            }, 230)
        } else {
            applyList()
            if (addedId != null && lm != null) {
                val position = adapter.positionOf(addedId)
                if (position >= 0) {
                    val last = lm.findLastVisibleItemPosition()
                    if (position > last) {
                        // 等插入布局完成后再滚动定位，避免同帧滚动+插入导致条目不显示。
                        binding.recycler.post {
                            binding.recycler.stopScroll()
                            (binding.recycler.layoutManager as? LinearLayoutManager)
                                ?.scrollToPositionWithOffset(position, 0)
                        }
                    }
                }
            }
        }
    }

    /** 侧栏标签列表：汇总所有笔记的标签。 */
    private fun renderTags() {
        val container = binding.tagList
        container.removeAllViews()
        val density = resources.displayMetrics.density
        val tags = allEntries.flatMap { it.tags }.distinct().sorted()
        if (tags.isEmpty()) {
            container.addView(
                TextView(this).apply {
                    text = getString(R.string.tags_empty)
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setPadding(
                        (24 * density).toInt(),
                        (8 * density).toInt(),
                        (24 * density).toInt(),
                        (8 * density).toInt()
                    )
                }
            )
            return
        }
        tags.forEach { tag ->
            val count = allEntries.count { tag in it.tags }
            container.addView(
                TextView(this).apply {
                    text = SpannableString("#$tag  $count").apply {
                        val suffixStart = "#$tag  ".length
                        setSpan(
                            ForegroundColorSpan(
                                ContextCompat.getColor(context, R.color.text_secondary)
                            ),
                            suffixStart,
                            length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        setSpan(
                            RelativeSizeSpan(0.82f),
                            suffixStart,
                            length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    if (tag == activeTag) setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(
                        (24 * density).toInt(),
                        (12 * density).toInt(),
                        (24 * density).toInt(),
                        (12 * density).toInt()
                    )
                    background = ContextCompat.getDrawable(
                        context,
                        TypedValue().also {
                            context.theme.resolveAttribute(
                                android.R.attr.selectableItemBackground,
                                it,
                                true
                            )
                        }.resourceId
                    )
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { selectTag(tag) }
                }
            )
        }
    }

    /** 点击标签：关闭侧栏只显示该标签的笔记；再次点击同一标签取消筛选。 */
    private fun selectTag(tag: String) {
        activeTag = if (activeTag == tag) null else tag
        binding.drawerRoot.closeDrawer(binding.drawerPanel)
        if (calendarVisible && activeTag != null) toggleCalendar()
        Toast.makeText(
            this,
            if (activeTag == null) getString(R.string.tag_filter_cleared)
            else getString(R.string.tag_filter_active, tag),
            Toast.LENGTH_SHORT
        ).show()
        renderTags()
        applyList()
    }

    private fun applyList(excludeId: String? = null) {
        val base = when {
            activeTag != null -> allEntries.filter { activeTag in it.tags }
            calendarVisible -> allEntries.filter { entry ->
                val day = Calendar.getInstance().apply { timeInMillis = entry.createdAt }
                day.get(Calendar.YEAR) == selectedYear &&
                    day.get(Calendar.MONTH) == selectedMonth &&
                    day.get(Calendar.DAY_OF_MONTH) == selectedDay
            }
            else -> allEntries
        }
        val entries = if (excludeId == null) base else base.filterNot { it.id == excludeId }
        adapter.submit(entries)
        displayedEntries = entries
        binding.emptyView.isVisible = entries.isEmpty()
        binding.recycler.isVisible = entries.isNotEmpty()
        binding.tagFilterBar.isVisible = activeTag != null
        binding.tagFilterBar.text = activeTag?.let { getString(R.string.tag_filter_bar, it) }.orEmpty()
        binding.emptySubtitle.text = getString(R.string.empty_subtitle)
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
            binding.appBar.setExpanded(true, false)
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
        // 一周从星期一开始，所以按 (星期几 - 星期一) 计算月初前的空格数。
        val leading = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * settings.previewScale)
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
        val scale = settings.previewScale
        binding.textCalMonth.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f * scale)
        binding.textCalYear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * scale)
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

    /** 随机挑一篇已有日记打开浏览页，上下篇可继续在全部日记间切换。 */
    private fun reviewRandom() {
        val entries = store.load().filter { !it.hidden }
        if (entries.isEmpty()) {
            Toast.makeText(this, R.string.random_review_empty, Toast.LENGTH_SHORT).show()
            return
        }
        // 打乱顺序，使上/下一篇切换也是随机顺序，并避免连续打开同一篇。
        val ids = entries.shuffled().map { it.id }
        var start = ids.indices.random()
        if (ids.size > 1 && ids[start] == lastRandomId) start = (start + 1) % ids.size
        lastRandomId = ids[start]
        startActivity(
            ViewDiaryActivity.intent(this, ids, start, randomReview = true)
        )
    }

    private fun confirmDelete(entry: DiaryEntry) {
        AlertDialog.Builder(this)
            .setMessage(R.string.delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                Feedback.deleted(this)
                animateRemoval(entry)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 删除动画只改变视图的绘制位置，不改动列表布局：
     * 被删行淡出，下方可见行整体上移，动画结束后才真正更新数据，
     * 因此列表滚动位置（屏幕顶部内容）全程不变，也不会出现跳动。
     */
    private fun animateRemoval(entry: DiaryEntry) {
        val manager = binding.recycler.layoutManager as? LinearLayoutManager
        val position = adapter.positionOf(entry.id)
        val holder = binding.recycler.findViewHolderForAdapterPosition(position)
        if (manager == null || position < 0 || holder == null) {
            removeEntry(entry.id)
            return
        }
        val row = holder.itemView
        val height = row.height
        if (height <= 0) {
            removeEntry(entry.id)
            return
        }
        binding.recycler.stopScroll()

        // 锚点：被删行上方最近的可见笔记及其屏幕位置。删除后让它停在原处，
        // 这样上方内容不会移动，下方条目仍按动画上移。
        var anchorId: String? = null
        var anchorTop = 0
        for (i in 0 until binding.recycler.childCount) {
            val child = binding.recycler.getChildAt(i)
            val childPos = binding.recycler.getChildAdapterPosition(child)
            if (childPos in 0 until position) {
                adapter.entryIdAt(childPos)?.let { id ->
                    if (anchorId == null) {
                        anchorId = id
                        anchorTop = child.top
                    }
                }
            }
        }
        // 只收集可见的被删行下方条目，它们上移的距离等于被删行高度。
        val below = mutableListOf<View>()
        for (i in 0 until binding.recycler.childCount) {
            val child = binding.recycler.getChildAt(i)
            val childPosition = binding.recycler.getChildAdapterPosition(child)
            if (childPosition > position) below.add(child)
        }

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                row.alpha = 1f - fraction
                below.forEach { child -> child.translationY = -height * fraction }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // 数据更新后让锚点笔记保持原屏幕位置，同时清除视觉位移并恢复动画器。
                    binding.recycler.viewTreeObserver.addOnPreDrawListener(
                        object : ViewTreeObserver.OnPreDrawListener {
                            override fun onPreDraw(): Boolean {
                                binding.recycler.viewTreeObserver.removeOnPreDrawListener(this)
                                below.forEach { child -> child.translationY = 0f }
                                binding.recycler.itemAnimator = defaultItemAnimator
                                return true
                            }
                        }
                    )
                    binding.recycler.itemAnimator = null
                    removeEntry(entry.id, anchorId, anchorTop)
                }
            })
            start()
        }
    }

    /** 先从界面移除该笔记（不带动画），再在后台写存储。 */
    private fun removeEntry(
        entryId: String,
        anchorId: String? = null,
        anchorTop: Int = 0
    ) {
        allEntries = allEntries.filterNot { it.id == entryId }
        binding.textNoteCount.text = getString(R.string.note_count, allEntries.size)
        renderTags()
        if (calendarVisible) renderCalendar()
        applyList()
        // 锚点笔记放在原来的屏幕位置：锚点在被删行上方，重新定位后上方内容固定不动。
        if (anchorId != null) {
            val newPos = adapter.positionOf(anchorId)
            if (newPos >= 0) {
                (binding.recycler.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(
                        newPos,
                        anchorTop - binding.recycler.paddingTop
                    )
            }
        }
        Thread { store.delete(entryId) }.start()
    }
}
