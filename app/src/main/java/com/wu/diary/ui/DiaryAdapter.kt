package com.wu.diary.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.SpannableString
import android.text.Spanned
import android.graphics.Typeface
import android.text.style.BackgroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.wu.diary.R
import com.wu.diary.data.Settings
import com.wu.diary.databinding.ItemDateBinding
import com.wu.diary.databinding.ItemDiaryBinding
import com.wu.diary.model.DiaryEntry
import com.wu.diary.util.GeoPoint
import com.wu.diary.util.ImageLoader
import com.wu.diary.util.MarkdownText
import com.wu.diary.util.TagChips
import com.wu.diary.util.VideoLoader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 按天分组的日记列表：同一天的记录共用一个日期头，记录之间用分割线隔开。 */
class DiaryAdapter(
    private val typeface: Typeface,
    private val fontScale: Float,
    private val previewLines: Int,
    private val lineSpacing: Float,
    private val tagStyle: Int,
    private val largeImages: Boolean,
    private val mediumImages: Boolean,
    private val style: Int = Settings.STYLE_MINIMAL,
    private val onClick: (DiaryEntry) -> Unit,
    private val onLongClick: (DiaryEntry) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** 卡片底色：memos 与卡片式都用圆角卡片，极简和时间线是原组列表。 */
    private val cardFrame = style == Settings.STYLE_MEMOS || style == Settings.STYLE_CARD

    /** 平铺卡片：memos 不显示日期头，每条笔记单独一张卡片。 */
    private val flatCards = style == Settings.STYLE_MEMOS

    /** 时间线：米色底 + 左侧竖线，日期头带圆点。 */
    private val timeline = style == Settings.STYLE_TIMELINE

    private sealed interface Row {
        data class DateHeader(
            val day: String,
            val year: String,
            val full: String,
            val isFirst: Boolean
        ) : Row
        data class Entry(val entry: DiaryEntry, val isGroupEnd: Boolean) : Row
    }

    private val rows = mutableListOf<Row>()
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val dayTextFormat = SimpleDateFormat("M/d", Locale.getDefault())
    private val yearTextFormat = SimpleDateFormat("yyyy", Locale.getDefault())
    private val fullDateTextFormat = SimpleDateFormat("yyyy年M月d日", Locale.getDefault())
    private val timeTextFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
    private var highlightQuery = ""

    fun submit(entries: List<DiaryEntry>, highlight: String = "") {
        val highlightChanged = highlightQuery != highlight
        highlightQuery = highlight
        val built = buildRows(entries)
        // 搜索关键词变化时整体重绑；其他情况用 DiffUtil 局部刷新，避免保存后整个列表闪烁。
        if (highlightChanged) {
            rows.clear()
            rows.addAll(built)
            notifyDataSetChanged()
            return
        }
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = rows.size
            override fun getNewListSize(): Int = built.size

            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                val old = rows[oldPos]
                val new = built[newPos]
                return when {
                    old is Row.DateHeader && new is Row.DateHeader -> old.day == new.day
                    old is Row.Entry && new is Row.Entry -> old.entry.id == new.entry.id
                    else -> false
                }
            }

            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                val old = rows[oldPos]
                val new = built[newPos]
                // 分组末尾只影响分割线样式，不算内容变化，避免删除后整行重绑闪一下。
                return when {
                    old is Row.Entry && new is Row.Entry -> old.entry == new.entry
                    old is Row.DateHeader && new is Row.DateHeader -> old == new
                    else -> false
                }
            }
        })
        rows.clear()
        rows.addAll(built)
        diff.dispatchUpdatesTo(this)
    }

    /**
     * 卡片风格：每条笔记一张浅灰圆角卡片，底部显示时间，右侧按钮打开操作菜单；
     * 时间线：米色底 + 左侧竖线；极简风格恢复原来的分组列表外观（透明背景 + 分割线）。
     */
    private fun applyCardChrome(binding: ItemDiaryBinding, density: Float, entry: DiaryEntry) {
        val root = binding.root
        val margins = root.layoutParams as? ViewGroup.MarginLayoutParams
        if (cardFrame) {
            root.background = GradientDrawable().apply {
                cornerRadius = 16 * density
                setColor(0xFFF1F1F4.toInt())
            }
            margins?.let {
                val gap = (6 * density).toInt()
                it.setMargins((16 * density).toInt(), gap, (16 * density).toInt(), gap)
                root.layoutParams = it
            }
            root.setPadding(
                (16 * density).toInt(),
                (16 * density).toInt(),
                (16 * density).toInt(),
                (12 * density).toInt()
            )
            setChildrenPadding(binding, 0, 0)
            binding.textBody.setTextColor(0xFF1C1C1C.toInt())
            binding.metaRow.isVisible = flatCards
            if (flatCards) {
                binding.textTime.text = timeTextFormat.format(Date(entry.createdAt))
                binding.textTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * fontScale)
                binding.btnMore.setOnClickListener { onLongClick(entry) }
            }
            binding.divider.isVisible = false
        } else if (timeline) {
            root.background = TimelineStripDrawable(
                background = TimelineStripDrawable.BACKGROUND,
                density = density
            )
            margins?.let {
                it.setMargins(0, 0, 0, 0)
                root.layoutParams = it
            }
            root.setPadding(0, (18 * density).toInt(), 0, (14 * density).toInt())
            setChildrenPadding(binding, (48 * density).toInt(), (20 * density).toInt())
            binding.textBody.setTextColor(0xFF1C1C1C.toInt())
            binding.metaRow.isVisible = false
            binding.divider.isVisible = false
        } else {
            val out = TypedValue()
            root.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
            root.setBackgroundResource(out.resourceId)
            margins?.let {
                it.setMargins(0, 0, 0, 0)
                root.layoutParams = it
            }
            root.setPadding(0, (20 * density).toInt(), 0, 0)
            setChildrenPadding(binding, (24 * density).toInt(), (24 * density).toInt())
            binding.textBody.setTextColor(ContextCompat.getColor(root.context, R.color.text_body))
            binding.metaRow.isVisible = false
            binding.divider.isVisible = true
        }
    }

    private fun setChildrenPadding(binding: ItemDiaryBinding, left: Int, right: Int) {
        binding.imageStrip.setPadding(left, 0, right, 0)
        binding.tagScroll.setPadding(left, 0, right, 0)
        binding.tagScrollBottom.setPadding(left, 0, right, 0)
        binding.textBody.setPadding(left, 0, right, 0)
        binding.metaRow.setPadding(left, 0, right, 0)
    }

    /** 把笔记列表展开成带日期头的行序列；卡片风格不显示日期头，每条笔记自成一张卡片。 */
    private fun buildRows(entries: List<DiaryEntry>): List<Row> {
        if (flatCards) {
            return entries.sortedByDescending { it.createdAt }
                .map { Row.Entry(it, false) }
        }
        val built = mutableListOf<Row>()
        var currentDay: String? = null
        entries.sortedByDescending { it.createdAt }.forEach { entry ->
            val date = Date(entry.createdAt)
            val day = dayFormat.format(date)
            if (day != currentDay) {
                built.add(
                    Row.DateHeader(
                        day = dayTextFormat.format(date),
                        year = yearTextFormat.format(date),
                        full = fullDateTextFormat.format(date),
                        isFirst = currentDay == null
                    )
                )
                currentDay = day
            }
            built.add(Row.Entry(entry, false))
        }
        val result = mutableListOf<Row>()
        built.forEachIndexed { index, row ->
            if (row is Row.Entry) {
                val next = built.getOrNull(index + 1)
                result.add(Row.Entry(row.entry, next == null || next is Row.DateHeader))
            } else {
                result.add(row)
            }
        }
        return result
    }

    /** 返回指定笔记在列表中的行位置，找不到时返回 -1。 */
    fun positionOf(entryId: String): Int =
        rows.indexOfFirst { it is Row.Entry && it.entry.id == entryId }

    /** 该行是否是日期头。 */
    fun isDateHeaderAt(position: Int): Boolean =
        rows.getOrNull(position) is Row.DateHeader

    /** 该行是否是所在日期分组的最后一条笔记（删除后日期头可能一并消失）。 */
    fun isGroupEndAt(position: Int): Boolean =
        (rows.getOrNull(position) as? Row.Entry)?.isGroupEnd == true

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
            val entryBinding = ItemDiaryBinding.inflate(inflater, parent, false)
            // 卡片/时间线风格用圆角底色或自绘背景 + 水波纹前景，点击反馈不会盖住底色。
            if (cardFrame || timeline) {
                entryBinding.root.foreground =
                    RippleDrawable(ColorStateList.valueOf(0x1F000000), null, null)
            }
            EntryViewHolder(entryBinding)
        }
    }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.DateHeader -> bindDate((holder as DateViewHolder).binding, row)
            is Row.Entry -> bindEntry((holder as EntryViewHolder).binding, row.entry, row.isGroupEnd)
        }
    }

    private fun bindDate(binding: ItemDateBinding, row: Row.DateHeader) {
        val density = binding.root.resources.displayMetrics.density
        val horizontal = (24 * density).toInt()
        if (timeline) {
            // 时间线：整行米色底 + 左侧竖线，日期头处画空心圆点。
            binding.root.background = TimelineStripDrawable(
                background = TimelineStripDrawable.BACKGROUND,
                density = density,
                dotCenterY = if (row.isFirst) 34f else 42f
            )
            binding.root.setPadding(
                (48 * density).toInt(),
                ((if (row.isFirst) 22 else 30) * density).toInt(),
                (20 * density).toInt(),
                (10 * density).toInt()
            )
            binding.textDate.text = row.full
            binding.textDate.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f * fontScale)
            binding.textYear.text = ""
            binding.textYear.isVisible = false
            return
        }
        binding.root.background = null
        binding.textDate.text = row.day
        binding.textYear.text = row.year
        binding.textYear.isVisible = true
        binding.textDate.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f * fontScale)
        binding.textYear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * fontScale)
        // 日期在“上方的分割线”和“第一条笔记”之间垂直居中（上方约 26dp、下方约 20dp）。
        val top = density * if (row.isFirst) 16 else 26
        binding.root.setPadding(
            horizontal,
            top.toInt(),
            horizontal,
            binding.root.paddingBottom
        )
    }

    private fun bindEntry(binding: ItemDiaryBinding, entry: DiaryEntry, isGroupEnd: Boolean) {
        val density = binding.root.resources.displayMetrics.density
        applyCardChrome(binding, density, entry)
        val body = listOf(entry.title.trim(), entry.content.trim())
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        val rendered = MarkdownText.render(body)
        val display = rendered?.text ?: body
        val styled = highlightBody(display, binding)
        // 先定好字号与字体再渲染列表，悬挂缩进需要按真实显示宽度测量。
        binding.textBody.typeface = typeface
        binding.textBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * fontScale)
        if (rendered != null) MarkdownText.style(styled, rendered, binding.textBody.paint)
        binding.textBody.text = styled
        binding.textBody.isVisible = display.isNotEmpty()
        binding.textBody.maxLines =
            if (previewLines == Settings.PREVIEW_ALL) Int.MAX_VALUE else previewLines
        binding.textBody.setLineSpacing(
            lineSpacing * binding.root.resources.displayMetrics.density,
            1f
        )
        // 与编辑页统一换行策略，保证列表预览的行数与编辑页一致。
        binding.textBody.breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
        binding.textBody.hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE

        // 预览标签与查看/编辑页一致，使用带光晕的标签色块；位置跟随设置。
        // 有地理坐标的笔记在标签前面加一个定位符号，显示位置与标签保持一致。
        val hasGeo = GeoPoint.findAll(entry.content).isNotEmpty()
        val hasMeta = entry.tags.isNotEmpty() || hasGeo
        val showMeta = tagStyle != Settings.TAG_HIDDEN && hasMeta
        binding.tagScroll.isVisible = showMeta && tagStyle == Settings.TAG_TOP
        binding.tagScrollBottom.isVisible = showMeta && tagStyle == Settings.TAG_BOTTOM
        binding.tagStrip.removeAllViews()
        binding.tagStripBottom.removeAllViews()
        if (showMeta) {
            val strip = if (tagStyle == Settings.TAG_TOP) binding.tagStrip else binding.tagStripBottom
            renderPreviewMeta(strip, entry, hasGeo)
        }

        // 每天最后一篇笔记下方的分割线贯通整屏，作为日期与日期之间的分隔。
        applyGroupEnd(binding, isGroupEnd)

        binding.imageStrip.removeAllViews()
        binding.imageStrip.isVisible = entry.images.isNotEmpty() || entry.videos.isNotEmpty()
        val metrics = binding.root.resources.displayMetrics
        // 卡片风格两侧各多出 16dp 内边距、时间线多出 20dp，图片宽度相应收窄。
        val cardInset = when {
            cardFrame -> 16 * metrics.density
            timeline -> 20 * metrics.density
            else -> 0f
        }
        if (largeImages) {
            // 大图模式：图片按原图比例铺满整行，纵向排列，带圆角。
            binding.imageStrip.orientation = LinearLayout.VERTICAL
            binding.imageStrip.gravity = Gravity.START
            val width = (metrics.widthPixels - 48 * metrics.density).toInt() - cardInset.toInt()
            val maxHeight = (metrics.heightPixels * 0.7f).toInt()
            entry.images.take(3).forEachIndexed { position, path ->
                val image = ImageLoader.largeImage(binding.root.context, path, width, maxHeight)
                    ?: return@forEachIndexed
                if (position > 0) {
                    (image.layoutParams as ViewGroup.MarginLayoutParams).topMargin =
                        (8 * metrics.density).toInt()
                }
                binding.imageStrip.addView(image)
            }
        } else if (mediumImages) {
            // 中图模式：不论原图多大都用同一块固定比例的圆角卡片，预览只显示第一张。
            binding.imageStrip.orientation = LinearLayout.VERTICAL
            binding.imageStrip.gravity = Gravity.CENTER_HORIZONTAL
            val width = (metrics.widthPixels - 50 * metrics.density).toInt() - cardInset.toInt()
            val height = width * 3 / 4 - (44 * metrics.density).toInt()
            entry.images.take(1).forEachIndexed { position, path ->
                val image = ImageLoader.cardImage(binding.root.context, path, width, height)
                    ?: return@forEachIndexed
                if (position > 0) {
                    (image.layoutParams as ViewGroup.MarginLayoutParams).topMargin =
                        (8 * metrics.density).toInt()
                }
                binding.imageStrip.addView(image)
            }
        } else {
            binding.imageStrip.orientation = LinearLayout.HORIZONTAL
            binding.imageStrip.gravity = Gravity.START
            val thumb = (78 * metrics.density).toInt()
            val gap = (8 * metrics.density).toInt()
            entry.images.take(3).forEach { path ->
                val image = ImageView(binding.root.context).apply {
                    layoutParams = LinearLayout.LayoutParams(thumb, thumb).apply { rightMargin = gap }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(ImageLoader.load(path, thumb))
                    background = GradientDrawable().apply {
                        cornerRadius = 7 * metrics.density
                    }
                    clipToOutline = true
                }
                binding.imageStrip.addView(image)
            }
        }

        // 带视频的笔记在预览里补上视频缩略图：中图模式只在没有图片时显示一张，
        // 大图模式接在图片下方，小图模式接在图片缩略图后面。
        if (entry.videos.isNotEmpty()) {
            when {
                mediumImages -> {
                    if (entry.images.isEmpty()) {
                        val width = (metrics.widthPixels - 50 * metrics.density).toInt() -
                            cardInset.toInt()
                        val height = width * 3 / 4 - (44 * metrics.density).toInt()
                        entry.videos.take(1).forEach { path ->
                            binding.imageStrip.addView(
                                videoCell(binding.root.context, path, width, height, 10f)
                            )
                        }
                    }
                }

                largeImages -> {
                    val width = (metrics.widthPixels - 48 * metrics.density).toInt() -
                        cardInset.toInt()
                    entry.videos.take(1).forEach { path ->
                        val cell = videoCell(binding.root.context, path, width, width * 9 / 16, 14f)
                        if (binding.imageStrip.childCount > 0) {
                            (cell.layoutParams as ViewGroup.MarginLayoutParams).topMargin =
                                (8 * metrics.density).toInt()
                        }
                        binding.imageStrip.addView(cell)
                    }
                }

                else -> {
                    val width = (78 * metrics.density).toInt()
                    val gap = (8 * metrics.density).toInt()
                    entry.videos.take(3).forEach { path ->
                        val cell = videoCell(binding.root.context, path, width, width * 9 / 16, 8f)
                        (cell.layoutParams as ViewGroup.MarginLayoutParams).rightMargin = gap
                        binding.imageStrip.addView(cell)
                    }
                }
            }
        }

        binding.root.setOnClickListener { onClick(entry) }
        binding.root.setOnLongClickListener { onLongClick(entry); true }
    }

    /** 标签行：先画定位符号（有坐标时），再画标签色块。 */
    private fun renderPreviewMeta(container: LinearLayout, entry: DiaryEntry, hasGeo: Boolean) {
        TagChips.render(container, entry.tags, fontScale)
        if (!hasGeo) return
        val density = container.resources.displayMetrics.density
        val icon = ImageView(container.context).apply {
            layoutParams = LinearLayout.LayoutParams(
                (16 * density).toInt(),
                (16 * density).toInt()
            ).apply {
                marginEnd = (6 * density).toInt()
                gravity = Gravity.CENTER_VERTICAL
            }
            setImageResource(R.drawable.ic_location)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setColorFilter(0xFF8C8C8C.toInt())
            contentDescription = container.context.getString(R.string.filter_has_location)
        }
        container.addView(icon, 0)
    }

    /** 预览用的视频封面卡片：封面帧 + 居中播放角标，圆角与对应图片模式一致。 */
    private fun videoCell(context: Context, path: String, widthPx: Int, heightPx: Int, radiusDp: Float): FrameLayout {
        val density = context.resources.displayMetrics.density
        val box = FrameLayout(context).apply {
            layoutParams = ViewGroup.MarginLayoutParams(widthPx, heightPx)
            background = GradientDrawable().apply { cornerRadius = radiusDp * density }
            clipToOutline = true
        }
        val thumb = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(VideoLoader.thumbnail(path, widthPx))
        }
        val play = ImageView(context).apply {
            val size = (20 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            background = ContextCompat.getDrawable(context, R.drawable.bg_play_badge)
            setImageResource(R.drawable.ic_play)
        }
        box.addView(thumb)
        box.addView(play)
        return box
    }

    /** 分组末尾的分割线贯通整屏，其他行两侧留出 24dp。 */
    private fun applyGroupEnd(binding: ItemDiaryBinding, isGroupEnd: Boolean) {
        val dividerMargin = if (isGroupEnd) {
            0
        } else {
            (24 * binding.root.resources.displayMetrics.density).toInt()
        }
        val dividerParams = binding.divider.layoutParams as ViewGroup.MarginLayoutParams
        if (dividerParams.marginStart != dividerMargin) {
            dividerParams.marginStart = dividerMargin
            dividerParams.marginEnd = dividerMargin
            binding.divider.layoutParams = dividerParams
        }
    }

    /** 只刷新可见行的分割线，用于分组末尾变化时不重绑整行。 */
    fun refreshGroupDividers(recycler: RecyclerView) {
        for (i in 0 until recycler.childCount) {
            val holder = recycler.getChildViewHolder(recycler.getChildAt(i)) as? EntryViewHolder
                ?: continue
            val position = holder.bindingAdapterPosition
            if (position < 0) continue
            val row = rows.getOrNull(position) as? Row.Entry ?: continue
            applyGroupEnd(holder.binding, row.isGroupEnd)
        }
    }

    /** 搜索状态下把命中的关键词加亮。 */
    private fun highlightBody(body: String, binding: ItemDiaryBinding): SpannableString {
        val spannable = SpannableString(body)
        val query = highlightQuery
        if (query.isEmpty() || body.isEmpty()) return spannable
        val color = ContextCompat.getColor(binding.root.context, R.color.search_highlight)
        val source = body.lowercase()
        val target = query.lowercase()
        var start = source.indexOf(target)
        while (start >= 0) {
            spannable.setSpan(
                BackgroundColorSpan(color),
                start,
                start + target.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            start = source.indexOf(target, start + target.length)
        }
        return spannable
    }

    companion object {
        private const val TYPE_DATE = 0
        private const val TYPE_ENTRY = 1
    }
}
