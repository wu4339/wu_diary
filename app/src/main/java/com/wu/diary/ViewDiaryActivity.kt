package com.wu.diary

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Layout
import android.text.SpannableString
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.text.util.Linkify
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import com.wu.diary.data.DiaryStore
import com.wu.diary.data.Settings
import com.wu.diary.databinding.ActivityViewDiaryBinding
import com.wu.diary.databinding.DialogNoteMenuBinding
import com.wu.diary.model.DiaryEntry
import com.wu.diary.util.Feedback
import com.wu.diary.util.ImageLoader
import com.wu.diary.util.LinkOpener
import com.wu.diary.util.MarkdownText
import com.wu.diary.util.TagChips
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** 只读的笔记浏览页：支持上一篇/下一篇、编辑、删除，图片可点开大图。 */
class ViewDiaryActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_IDS = "entry_ids"
        private const val EXTRA_INDEX = "entry_index"
        private const val EXTRA_RANDOM_REVIEW = "random_review"

        fun intent(context: Context, ids: List<String>, index: Int, randomReview: Boolean = false): Intent =
            Intent(context, ViewDiaryActivity::class.java)
                .putStringArrayListExtra(EXTRA_IDS, ArrayList(ids))
                .putExtra(EXTRA_INDEX, index)
                .putExtra(EXTRA_RANDOM_REVIEW, randomReview)
    }

    private lateinit var binding: ActivityViewDiaryBinding
    private lateinit var store: DiaryStore

    private var ids: List<String> = emptyList()
    private var index = 0
    private var entry: DiaryEntry? = null
    private var randomReview = false

    /** 翻书动画进行中，避免连续滑动打断。 */
    private var flipping = false

    /** 查看页字号缩放，用于标签色块。 */
    private var tagScale = 1f

    /** 无序列表渲染后的文本，双击定位时用来把显示偏移换算回原文偏移。 */
    private var renderedText: MarkdownText.Rendered? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewDiaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = DiaryStore(this)
        ids = intent.getStringArrayListExtra(EXTRA_IDS) ?: emptyList()
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (ids.size - 1).coerceAtLeast(0))

        val settings = Settings(this)
        val scale = settings.readingScale
        tagScale = scale
        binding.textDay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f * scale)
        binding.textYear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * scale)
        binding.textContent.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.readingFontSp.toFloat())
        binding.textContent.typeface = settings.typeface
        binding.textContent.setLineSpacing(
            settings.readingLineSpacing * resources.displayMetrics.density,
            1f
        )
        // 与编辑页保持一致的中文换行策略，否则同一段文字在查看页和编辑页的折行位置会不同。
        binding.textContent.breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        binding.textContent.hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        binding.textContent.movementMethod = LinkMovementMethod.getInstance()

        binding.btnClose.setOnClickListener { finish() }
        binding.btnMore.setOnClickListener { showMenu() }
        binding.btnTags.setOnClickListener { editTags() }
        binding.btnPrev.setOnClickListener { step(-1) }
        binding.btnNext.setOnClickListener { step(1) }
        randomReview = intent.getBooleanExtra(EXTRA_RANDOM_REVIEW, false)
        binding.titleBox.isVisible = randomReview
    }

    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeActive = false
    private var swipeFromImages = false

    /**
     * 整页范围识别双击；左右滑动切换只在随机回顾里启用，普通查看页不响应滑动。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        doubleTapDetector.onTouchEvent(ev)
        if (randomReview && handleSwipe(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    /** 随机回顾：按横向拖动距离判断左右滑动，返回 true 表示已切换笔记。 */
    private fun handleSwipe(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeStartX = ev.x
                swipeStartY = ev.y
                swipeActive = true
                swipeFromImages = isInImageStrip(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                if (swipeActive) {
                    val dx = abs(ev.x - swipeStartX)
                    val dy = abs(ev.y - swipeStartY)
                    // 纵向拖动用于滚动内容，不再当作左右切换。
                    if (dy > dx * 1.2f && dy > ViewConfiguration.get(this).scaledTouchSlop) {
                        swipeActive = false
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (swipeActive && !swipeFromImages) {
                    val dx = ev.x - swipeStartX
                    val dy = abs(ev.y - swipeStartY)
                    val threshold = 48 * resources.displayMetrics.density
                    if (abs(dx) > threshold && abs(dx) > dy * 1.2f) {
                        swipeActive = false
                        step(if (dx < 0) 1 else -1, flip = true)
                        return true
                    }
                }
                swipeActive = false
            }
            MotionEvent.ACTION_CANCEL -> swipeActive = false
        }
        return false
    }

    /** 浏览页手势：双击进入编辑（可在设置中开启）。 */
    private val doubleTapDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (Settings(this@ViewDiaryActivity).doubleTapEdit) {
                    openEditor(offsetAt(e))
                    return true
                }
                return false
            }
        })
    }

    /** 双击位置对应到正文中的字符偏移，进入编辑页后光标落在这里。 */
    private fun offsetAt(e: MotionEvent): Int {
        val target = binding.textContent
        val text = target.text?.toString().orEmpty()
        val layout = target.layout ?: return -1
        val location = IntArray(2)
        target.getLocationOnScreen(location)
        val x = e.rawX - location[0] - target.totalPaddingLeft
        val y = e.rawY - location[1] - target.totalPaddingTop
        if (y < 0 || text.isEmpty()) return -1
        val line = layout.getLineForVertical(y.toInt())
        val displayOffset = layout.getOffsetForHorizontal(line, x).coerceIn(0, text.length)
        return MarkdownText.sourceOffset(renderedText, displayOffset)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val all = store.load()
        entry = ids.getOrNull(index)?.let { id -> all.find { it.id == id } }
        if (entry == null) {
            finish()
            return
        }
        render()
    }

    private fun render() {
        val current = entry ?: return

        val date = Date(current.createdAt)
        binding.textDay.text = SimpleDateFormat("M/d", Locale.getDefault()).format(date)
        binding.textYear.text = SimpleDateFormat("yyyy", Locale.getDefault()).format(date)
        binding.textPosition.text = getString(R.string.note_position, index + 1, ids.size)

        val text = listOf(current.title, current.content)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        val rendered = MarkdownText.render(text)
        renderedText = rendered
        binding.textContent.text = linkify(rendered?.text ?: text)
        binding.textContent.movementMethod = LinkMovementMethod.getInstance()
        binding.textContent.isVisible = text.isNotEmpty()

        TagChips.render(binding.tagStrip, current.tags, tagScale)

        renderImages(current)
        updateNav()
    }

    /** 识别正文里的网址，并换成点击时优先用对应 App 打开的 span。 */
    private fun linkify(text: String): CharSequence {
        val spannable = SpannableString(text)
        Linkify.addLinks(spannable, Linkify.WEB_URLS)
        spannable.getSpans(0, spannable.length, URLSpan::class.java).forEach { span ->
            val start = spannable.getSpanStart(span)
            val end = spannable.getSpanEnd(span)
            val flags = spannable.getSpanFlags(span)
            val url = span.url
            spannable.removeSpan(span)
            spannable.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    LinkOpener.open(this@ViewDiaryActivity, Uri.parse(url))
                }

                override fun updateDrawState(ds: TextPaint) {
                    super.updateDrawState(ds)
                    ds.isUnderlineText = false
                }
            }, start, end, flags)
        }
        return spannable
    }

    private fun renderImages(current: DiaryEntry) {
        binding.imageStrip.removeAllViews()
        binding.imageScroll.isVisible = current.images.isNotEmpty()
        val density = resources.displayMetrics.density
        val size = (84 * density).toInt()
        val gap = (8 * density).toInt()
        current.images.forEach { path ->
            val image = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply { rightMargin = gap }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(ImageLoader.load(path, size))
            }
            image.setOnClickListener { openImage(path) }
            binding.imageStrip.addView(image)
        }
    }

    private fun updateNav() {
        val hasPrev = index > 0
        val hasNext = index < ids.lastIndex
        binding.btnPrev.apply {
            isEnabled = hasPrev
            alpha = if (hasPrev) 1f else 0.25f
        }
        binding.btnNext.apply {
            isEnabled = hasNext
            alpha = if (hasNext) 1f else 0.25f
        }
    }

    private fun step(delta: Int, flip: Boolean = false) {
        if (flipping) return
        val target = index + delta
        if (target !in ids.indices) {
            if (randomReview) {
                val message = if (delta < 0) R.string.random_review_first else R.string.random_review_last
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
            return
        }
        // 随机回顾里切换笔记时播放翻书声。
        if (randomReview) Feedback.pageTurn(this)
        // 随机回顾里左右滑动时用翻书效果切换，其他情况直接切换。
        if (flip && randomReview) {
            flipTo(target, delta)
            return
        }
        index = target
        load()
        binding.scrollContent.scrollTo(0, 0)
    }

    /** 随机回顾：以书脊为轴把当前页翻走，再翻入上一篇/下一篇。 */
    private fun flipTo(target: Int, delta: Int) {
        val page = binding.scrollContent
        flipping = true
        page.animate().cancel()
        // 向后翻以左边缘为轴，向前翻以右边缘为轴，视觉上像沿书脊翻页。
        page.pivotX = if (delta > 0) 0f else page.width.toFloat()
        page.pivotY = page.height / 2f
        page.animate()
            .rotationY(if (delta > 0) 90f else -90f)
            .setDuration(160L)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                index = target
                load()
                if (isFinishing || isDestroyed) {
                    flipping = false
                    return@withEndAction
                }
                page.scrollTo(0, 0)
                page.rotationY = if (delta > 0) -90f else 90f
                page.animate()
                    .rotationY(0f)
                    .setDuration(180L)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction { flipping = false }
                    .start()
            }
            .start()
    }

    /** 触摸起点是否落在图片横向列表上。 */
    private fun isInImageStrip(event: MotionEvent): Boolean {
        val strip = binding.imageScroll
        if (!strip.isVisible) return false
        val location = IntArray(2)
        strip.getLocationOnScreen(location)
        val x = event.rawX
        val y = event.rawY
        return x >= location[0] && x <= location[0] + strip.width &&
            y >= location[1] && y <= location[1] + strip.height
    }

    private fun openEditor(cursor: Int = -1) {
        entry?.let { startActivity(EditDiaryActivity.intent(this, it.id, cursor = cursor)) }
    }

    /** 全屏查看图片，点击任意位置关闭。 */
    private fun openImage(path: String) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val screenSize = maxOf(
            resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels
        )
        val image = ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setImageBitmap(
                ImageLoader.load(path, screenSize) ?: BitmapFactory.decodeFile(path)
            )
        }
        image.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(image)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            // 让内容铺满整屏并绘制到系统栏下面，状态栏/导航栏也就变成纯黑。
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            WindowCompat.setDecorFitsSystemWindows(this, false)
            statusBarColor = Color.BLACK
            navigationBarColor = Color.BLACK
            WindowInsetsControllerCompat(this, image).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
        }
        dialog.show()
    }

    private fun showMenu() {
        val dialog = Dialog(this)
        val menu = DialogNoteMenuBinding.inflate(layoutInflater)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(menu.root)

        menu.itemEdit.setOnClickListener {
            dialog.dismiss()
            entry?.let { startActivity(EditDiaryActivity.intent(this, it.id)) }
        }
        menu.itemCopy.setOnClickListener {
            dialog.dismiss()
            val text = listOf(entry?.title, entry?.content)
                .filter { !it.isNullOrBlank() }
                .joinToString("\n")
            if (text.isEmpty()) return@setOnClickListener
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("wu_diary", text))
            Toast.makeText(this, R.string.note_copied, Toast.LENGTH_SHORT).show()
        }
        menu.itemTag.setOnClickListener {
            dialog.dismiss()
            editTags()
        }
        menu.itemDelete.setOnClickListener {
            dialog.dismiss()
            confirmDelete()
        }
        val current = entry
        menu.itemHide.isVisible = current != null
        if (current?.hidden == true) {
            menu.textHide.text = getString(R.string.unhide_note)
            menu.iconHide.setImageResource(R.drawable.ic_show)
        }
        menu.itemHide.setOnClickListener {
            dialog.dismiss()
            if (current == null) return@setOnClickListener
            store.save(
                current.copy(hidden = !current.hidden, updatedAt = System.currentTimeMillis())
            )
            Toast.makeText(
                this,
                if (current.hidden) R.string.note_unhidden else R.string.note_hidden,
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }

        val density = resources.displayMetrics.density
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.25f)
            setGravity(Gravity.TOP or Gravity.END)
            setLayout((210 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            attributes = attributes.apply {
                y = (56 * density).toInt()
                x = (8 * density).toInt()
            }
        }
        dialog.show()
    }

    /** 查看页里直接给当前笔记加/删标签，保存后立即刷新标签行。 */
    private fun editTags() {
        val current = entry ?: return
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val existingTags = store.load()
            .flatMap { it.tags }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val input = EditText(this).apply {
            setText(current.tags.joinToString(" "))
            hint = getString(R.string.tags_hint)
            setSingleLine(true)
            setPadding(dp(4), dp(10), dp(4), dp(10))
        }
        container.addView(input)

        if (existingTags.isNotEmpty()) {
            container.addView(
                TextView(this).apply {
                    text = getString(R.string.existing_tags)
                    setTextColor(ContextCompat.getColor(this@ViewDiaryActivity, R.color.text_secondary))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, dp(16), 0, dp(6))
                }
            )
            val chipViews = mutableListOf<Pair<String, TextView>>()

            fun refreshChips() {
                val selectedTags = parseTags(input.text.toString())
                chipViews.forEach { (tag, view) ->
                    val selected = tag in selectedTags
                    view.setTextColor(
                        ContextCompat.getColor(
                            this@ViewDiaryActivity,
                            if (selected) R.color.text_primary else R.color.text_secondary
                        )
                    )
                    view.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                }
            }

            // 现有标签按宽度自动换行，最多显示 3 行，更多时上下滑动选择。
            // 弹窗的自定义视图宽度在展示前是 0，这里按屏幕宽度估算内容宽度，保证标签一定能显示。
            val rowHeight = dp(40)
            val contentWidth =
                (minOf(resources.displayMetrics.widthPixels, dp(560)) - dp(88)).coerceAtLeast(dp(160))
            val chipArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val chipScroll = ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(chipArea)
            }
            container.addView(
                chipScroll,
                LinearLayout.LayoutParams(contentWidth, rowHeight * 3)
            )

            fun layoutChips(width: Int) {
                if (width <= 0 || chipArea.childCount > 0) return
                var row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                var used = 0
                chipViews.forEach { (_, chip) ->
                    (chip.parent as? ViewGroup)?.removeView(chip)
                    chip.measure(
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    )
                    val chipWidth = chip.measuredWidth + dp(8)
                    if (used > 0 && used + chipWidth > width) {
                        chipArea.addView(
                            row,
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                rowHeight
                            )
                        )
                        row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                        used = 0
                    }
                    row.addView(
                        chip,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = dp(8) }
                    )
                    used += chipWidth
                }
                if (row.childCount > 0) {
                    chipArea.addView(
                        row,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            rowHeight
                        )
                    )
                }
                chipScroll.layoutParams = chipScroll.layoutParams.apply {
                    height = rowHeight * minOf(3, chipArea.childCount)
                }
            }

            existingTags.forEach { tag ->
                val chip = TextView(this).apply {
                    text = "#$tag"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    isClickable = true
                    setOnClickListener {
                        val selectedTags = parseTags(input.text.toString()).toMutableList()
                        if (tag in selectedTags) selectedTags.remove(tag) else selectedTags.add(tag)
                        input.setText(selectedTags.joinToString(" "))
                        input.setSelection(input.text.length)
                        refreshChips()
                    }
                }
                chipViews.add(tag to chip)
            }
            layoutChips(contentWidth)
            refreshChips()
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.tag_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val updated = current.copy(
                    tags = parseTags(input.text.toString()),
                    updatedAt = System.currentTimeMillis()
                )
                store.save(updated)
                entry = updated
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun parseTags(raw: String): List<String> =
        raw.split(Regex("[\\s,，、]+"))
            .map { it.trim().removePrefix("#") }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(10)

    private fun confirmDelete() {
        val current = entry ?: return
        AlertDialog.Builder(this)
            .setMessage(R.string.delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                store.delete(current.id)
                Feedback.deleted(this)
                if (!randomReview) {
                    finish()
                    return@setPositiveButton
                }
                // 随机回顾里删除后继续看下一篇；删的是最后一篇则回到上一篇。
                val remaining = ids.filterNot { it == current.id }
                if (remaining.isEmpty()) {
                    finish()
                    return@setPositiveButton
                }
                ids = remaining
                index = index.coerceAtMost(remaining.lastIndex)
                load()
                binding.scrollContent.scrollTo(0, 0)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
