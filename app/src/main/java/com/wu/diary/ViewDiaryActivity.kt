package com.wu.diary

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ImageSpan
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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.wu.diary.data.DiaryStore
import com.wu.diary.data.Settings
import com.wu.diary.databinding.ActivityViewDiaryBinding
import com.wu.diary.databinding.DialogNoteMenuBinding
import com.wu.diary.model.DiaryEntry
import org.json.JSONObject
import com.wu.diary.util.Feedback
import com.wu.diary.util.GeoName
import com.wu.diary.util.GeoPoint
import com.wu.diary.util.ImageLoader
import com.wu.diary.util.LinkOpener
import com.wu.diary.util.LocationLines
import com.wu.diary.util.MarkdownText
import com.wu.diary.util.TagEditor
import com.wu.diary.util.TagChips
import com.wu.diary.util.VideoLoader
import com.wu.diary.util.showDeleteConfirm
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** 只读的笔记浏览页：支持上一篇/下一篇、编辑、删除，图片可点开大图。 */
class ViewDiaryActivity : BaseActivity() {

    companion object {
        private const val EXTRA_IDS = "entry_ids"
        private const val EXTRA_INDEX = "entry_index"
        private const val EXTRA_RANDOM_REVIEW = "random_review"

        /** 编辑页保存后跳转时携带的最新笔记内容，首帧直接渲染，避免先显示旧内容再跳变。 */
        const val EXTRA_FOCUS_ENTRY = "focus_entry"

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

    /** 编辑页带来的最新内容，第一次 load() 时优先使用，之后仍以存储为准。 */
    private var pendingEntry: DiaryEntry? = null

    /** 翻书动画进行中，避免连续滑动打断。 */
    private var flipping = false

    /** 查看页字号缩放，用于标签色块。 */
    private var tagScale = 1f

    /** 正在等待定位回调，避免重复发起请求。 */
    private var locationWaiting = false

    private val requestLocation =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.any { it }) {
                fetchCurrentLocation()
            } else {
                Toast.makeText(this, R.string.location_failed, Toast.LENGTH_SHORT).show()
            }
        }

    /** 无序列表渲染后的文本，双击定位时用来把显示偏移换算回原文偏移。 */
    private var renderedText: MarkdownText.Rendered? = null

    /** 中图模式下横向翻页的图片列表。 */
    private var pagerPaths: List<String> = emptyList()
    private var pagerCardWidth = 0
    private var pagerCardHeight = 0
    /** 当前高亮的页码圆点。 */
    private var pagerDotPosition = 0

    /** 中图模式横向翻页的单元格，只承载一张圆角卡片。 */
    private class ImagePagerHolder(val box: FrameLayout) : RecyclerView.ViewHolder(box)

    /** 中图模式：一页一张图片，左右滑动切换。 */
    private inner class ImagePagerAdapter : RecyclerView.Adapter<ImagePagerHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImagePagerHolder {
            val box = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            return ImagePagerHolder(box)
        }

        override fun getItemCount(): Int = pagerPaths.size

        override fun onBindViewHolder(holder: ImagePagerHolder, position: Int) {
            val box = holder.box
            box.removeAllViews()
            val path = pagerPaths.getOrNull(position) ?: return
            val image = ImageLoader.cardImage(
                this@ViewDiaryActivity, path, pagerCardWidth, pagerCardHeight
            ) ?: return
            image.layoutParams = FrameLayout.LayoutParams(
                pagerCardWidth,
                pagerCardHeight,
                Gravity.CENTER_HORIZONTAL
            )
            image.setOnClickListener { openImage(path) }
            box.addView(image)
        }
    }

    private val imagePagerAdapter = ImagePagerAdapter()

    /** 翻页过程中把页码圆点同步到居中的那一张。 */
    private val imagePagerScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            val position = nearestImagePosition()
            if (position != pagerDotPosition) {
                pagerDotPosition = position
                renderDots(pagerPaths.size, position)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewDiaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = DiaryStore(this)
        ids = intent.getStringArrayListExtra(EXTRA_IDS) ?: emptyList()
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (ids.size - 1).coerceAtLeast(0))
        intent.getStringExtra(EXTRA_FOCUS_ENTRY)?.let { json ->
            runCatching { DiaryEntry.fromJson(JSONObject(json)) }.getOrNull()?.let { fresh ->
                if (ids.getOrNull(index) == fresh.id) pendingEntry = fresh
            }
        }

        val settings = Settings(this)
        val scale = settings.readingScale
        // 标签色块与主界面预览保持一致的字号，不跟随查看页正文放大。
        tagScale = settings.previewScale
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
        // 关掉边缘拉伸，顶部下拉时改用自绘的跟随位移，避免两种效果叠在一起。
        binding.scrollContent.overScrollMode = View.OVER_SCROLL_NEVER
        // 中图模式下图片用横向分页：一页一张，滑动切换。
        binding.imagePager.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        PagerSnapHelper().attachToRecyclerView(binding.imagePager)
        binding.imagePager.adapter = imagePagerAdapter
        binding.imagePager.addOnScrollListener(imagePagerScrollListener)
    }

    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeActive = false
    private var swipeFromImages = false

    /**
     * 整页范围识别双击；顶部下拉切换下一篇；左右滑动切换只在随机回顾里启用。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        doubleTapDetector.onTouchEvent(ev)
        if (handlePull(ev)) return true
        if (randomReview && handleSwipe(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    private var pullStartX = 0f
    private var pullStartY = 0f
    /** 本次手势是从页面顶部开始的纵向下拉。 */
    private var pullActive = false
    /** 下拉距离已经超过触发阈值。 */
    private var pullReady = false
    /** 正在播放下拉跳转动画，期间不再响应手势。 */
    private var jumping = false
    /** 提示文案当前是否处于“松手即可切换”的状态。 */
    private var pullHintRelease = false

    /**
     * 内容位于顶部时向下拉：页面跟随手指下移（带阻尼），
     * 松手超过阈值就随机跳到其他笔记，未超过则回弹。
     */
    private fun handlePull(ev: MotionEvent): Boolean {
        val density = resources.displayMetrics.density
        val threshold = 72 * density
        val maxPull = 140 * density
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pullStartX = ev.x
                pullStartY = ev.y
                // 上一段回弹动画可能还在跑，先停掉，避免它继续改写 translationY。
                if (!jumping) binding.scrollContent.animate().cancel()
                pullActive = binding.scrollContent.scrollY <= 0 && !jumping && !flipping
                pullReady = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!pullActive) return false
                val dy = ev.y - pullStartY
                val dx = abs(ev.x - pullStartX)
                // 只认纵向向下拖动；一旦向上或横向为主就放弃，避免抢走滚动和左右滑动。
                if (dy <= 0 || dx > dy * 0.6f) {
                    pullActive = false
                    binding.scrollContent.translationY = 0f
                    hidePullHint()
                    return false
                }
                // 阻尼跟随：手指移动越多，页面下移越缓，最多 140dp。
                binding.scrollContent.translationY = (dy * 0.5f).coerceAtMost(maxPull)
                val ready = dy > threshold
                if (ready) pullReady = true
                showPullHint((dy.toFloat() / threshold).coerceIn(0f, 1f), ready)
            }
            MotionEvent.ACTION_UP -> {
                val triggered = pullActive && pullReady
                pullActive = false
                pullReady = false
                hidePullHint()
                if (triggered) {
                    animatePullJump()
                    return true
                }
                // 没拉够：回弹到原位。
                resetPull()
            }
            MotionEvent.ACTION_CANCEL -> {
                pullActive = false
                pullReady = false
                hidePullHint()
                resetPull()
            }
        }
        return false
    }

    private fun resetPull() {
        val page = binding.scrollContent
        if (page.translationY == 0f) return
        page.animate().cancel()
        page.animate()
            .translationY(0f)
            .setDuration(160)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** 下拉时显示顶部提示：越拉越清楚，拉够后提示改为松手即切换。 */
    private fun showPullHint(progress: Float, ready: Boolean) {
        val hint = binding.pullHint
        if (hint.visibility != View.VISIBLE) hint.visibility = View.VISIBLE
        hint.alpha = progress
        if (pullHintRelease != ready) {
            pullHintRelease = ready
            hint.text = getString(
                if (ready) R.string.pull_random_release else R.string.pull_random_hint
            )
        }
    }

    private fun hidePullHint() {
        pullHintRelease = false
        val hint = binding.pullHint
        if (hint.visibility != View.VISIBLE) return
        hint.animate().cancel()
        hint.animate()
            .alpha(0f)
            .setDuration(120)
            .withEndAction {
                if (!isFinishing && !isDestroyed) hint.visibility = View.INVISIBLE
            }
            .start()
    }

    /** 松手后立即换成随机笔记，新内容轻微上移，避免滑出后等待的白屏。 */
    private fun animatePullJump() {
        val target = randomOtherIndex() ?: run {
            // 只有一篇笔记：没有可跳转的目标，回弹即可。
            resetPull()
            return
        }
        jumping = true
        val page = binding.scrollContent
        page.animate().cancel()

        // 随机切换与保存用同一种提示音。
        Feedback.saved(this)
        index = target
        load()
        page.scrollTo(0, 0)
        // 不做滑出等待，也不淡入：直接换内容后只做轻微上移，
        // 内容全程不透明，切换过程中不会露出白底。
        page.translationY = 10 * resources.displayMetrics.density
        page.alpha = 1f
        page.animate()
            .translationY(0f)
            .setDuration(110)
            .setInterpolator(DecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                // 动画被新的触摸打断时 withEndAction 不会执行，
                // 这里结束和取消都清标志，避免 jumping 卡死导致之后再也无法下拉。
                override fun onAnimationEnd(animation: Animator) {
                    jumping = false
                }

                override fun onAnimationCancel(animation: Animator) {
                    jumping = false
                }
            })
            .start()
    }

    /** 随机挑一篇不是当前笔记的笔记位置；只有一篇时返回 null。 */
    private fun randomOtherIndex(): Int? {
        if (ids.size <= 1) return null
        val candidates = ids.indices.filter { it != index }
        return candidates.random()
    }

    /** 随机回顾：按横向拖动距离判断左右滑动，返回 true 表示已切换笔记。 */
    private fun handleSwipe(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeStartX = ev.x
                swipeStartY = ev.y
                swipeActive = !jumping && !flipping
                swipeFromImages = isInImageStrip(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                if (swipeActive) {
                    val dx = abs(ev.x - swipeStartX)
                    val dy = abs(ev.y - swipeStartY)
                    // 纵向成分一接近横向就按滚动处理，不再当作左右切换。
                    // 原来要求 dy > dx * 1.2，斜着下拉很容易被误判成横滑。
                    if (dy > dx * 0.6f && dy > ViewConfiguration.get(this).scaledTouchSlop) {
                        swipeActive = false
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (swipeActive && !swipeFromImages) {
                    val dx = ev.x - swipeStartX
                    val dy = abs(ev.y - swipeStartY)
                    val threshold = 48 * resources.displayMetrics.density
                    // 必须是非常明显的横向：水平位移远大于纵向，且纵向几乎没移动。
                    if (abs(dx) > threshold && abs(dx) > dy * 1.5f && dy < threshold) {
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

    /** 正在删除并关闭本页：期间忽略数据变更广播，避免主线程又去读盘拖慢退出。 */
    private var closing = false

    /** 后台保存/删除完成后的广播：重新读取数据，保证查看页内容最新。 */
    private val dataChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // 删除当前笔记时会先广播再关闭页面，这时再读盘会让退出动画卡住好几秒。
            if (closing) return
            load()
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            dataChangedReceiver,
            IntentFilter(DiaryStore.ACTION_DATA_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(dataChangedReceiver)
    }

    private fun load() {
        val targetId = ids.getOrNull(index)
        // 编辑页刚保存的内容直接使用，避免存储尚未写完时先显示旧内容。
        val fresh = pendingEntry?.takeIf { it.id == targetId }
        pendingEntry = null
        entry = fresh ?: targetId?.let { id -> store.load().find { it.id == id } }
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
        val styled = linkify(rendered?.text ?: text)
        if (rendered != null) MarkdownText.style(styled, rendered, binding.textContent.paint)
        binding.textContent.text = styled
        binding.textContent.movementMethod = LinkMovementMethod.getInstance()
        binding.textContent.isVisible = text.isNotEmpty()

        TagChips.render(binding.tagStrip, current.tags, tagScale, onClick = { editTags() }, tile = true)
        // 有标签后标签图标隐藏，位置换成标签色块。
        binding.tagStrip.isVisible = current.tags.isNotEmpty()
        binding.btnTags.isVisible = current.tags.isEmpty()

        // 已有坐标就打开地图；还没有坐标时先记录当前位置，记完再点才打开地图。
        val geo = GeoPoint.findAll(text).firstOrNull()?.let { it.lat to it.lng }
        binding.btnViewGeo.setOnClickListener {
            if (geo != null) {
                GeoPoint.openMap(this, geo.first, geo.second)
            } else {
                fetchCurrentLocation()
            }
        }

        renderImages(current)
        renderVideos(current)
        updateNav()
    }

    /** 网址与定位行都用紫色下划线 + 外链图标的样式显示，点击时打开对应 App / 地图。 */
    private fun linkify(text: String): Spannable {
        val spannable = SpannableStringBuilder(text)
        Linkify.addLinks(spannable, Linkify.WEB_URLS)
        val iconPositions = mutableListOf<Int>()
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
                    ds.color = LocationLines.LINK_COLOR
                    ds.isUnderlineText = true
                }
            }, start, end, flags)
            iconPositions.add(end)
        }
        // 整行定位信息都可点击（地名 + 坐标），样式和网址链接一致。
        LocationLines.link(spannable)
        iconPositions.addAll(LocationLines.lineEnds(spannable))
        // 在每个链接后面插入一个空格并放外链图标；从后往前插入，避免前面的位置偏移。
        iconPositions.distinct().sortedDescending().forEach { position ->
            // 先插一个空格留出间隔，图标再占一格。
            spannable.insert(position, "  ")
            val icon = ContextCompat.getDrawable(this, R.drawable.ic_external_link)
                ?: return@forEach
            spannable.setSpan(
                ImageSpan(icon, ImageSpan.ALIGN_CENTER),
                position + 1,
                position + 2,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return spannable
    }

    private fun renderImages(current: DiaryEntry) {
        binding.imageStrip.removeAllViews()
        binding.imageColumn.removeAllViews()
        val style = Settings(this).imageStyle
        val hasImages = current.images.isNotEmpty()
        binding.imageScroll.isVisible = style == Settings.IMAGE_SMALL && hasImages
        binding.imageColumn.isVisible = style == Settings.IMAGE_LARGE && hasImages
        binding.imagePagerBox.isVisible = style == Settings.IMAGE_MEDIUM && hasImages
        val metrics = resources.displayMetrics
        val density = metrics.density
        val width = (metrics.widthPixels - 50 * density).toInt()
        val height = width * 3 / 4 - (44 * density).toInt()
        when (style) {
            Settings.IMAGE_MEDIUM -> {
                // 中图模式：一页一张固定比例的圆角卡片，左右滑动切换。
                pagerPaths = current.images
                pagerCardWidth = width
                pagerCardHeight = height
                pagerDotPosition = 0
                imagePagerAdapter.notifyDataSetChanged()
                binding.imagePager.scrollToPosition(0)
                renderDots(current.images.size, 0)
            }

            Settings.IMAGE_LARGE -> {
                // 大图模式：每张图片按原图比例铺满正文宽度，纵向排列，带圆角。
                val maxHeight = (metrics.heightPixels * 0.7f).toInt()
                current.images.forEachIndexed { position, path ->
                    val image = ImageLoader.largeImage(
                        this, path, (metrics.widthPixels - 48 * density).toInt(), maxHeight
                    ) ?: return@forEachIndexed
                    if (position > 0) {
                        (image.layoutParams as ViewGroup.MarginLayoutParams).topMargin =
                            (8 * density).toInt()
                    }
                    image.setOnClickListener { openImage(path) }
                    binding.imageColumn.addView(image)
                }
            }

            else -> {
                val size = (84 * density).toInt()
                val gap = (8 * density).toInt()
                current.images.forEach { path ->
                    val image = ImageView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(size, size).apply {
                            rightMargin = gap
                        }
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setImageBitmap(ImageLoader.load(path, size))
                        background = GradientDrawable().apply {
                            cornerRadius = 7 * density
                        }
                        clipToOutline = true
                    }
                    image.setOnClickListener { openImage(path) }
                    binding.imageStrip.addView(image)
                }
            }
        }
    }

    /** 图片下方的视频区域：小图模式横排缩略图，中图/大图模式显示大卡片。 */
    private fun renderVideos(current: DiaryEntry) {
        binding.videoStrip.removeAllViews()
        binding.videoColumn.removeAllViews()
        val style = Settings(this).imageStyle
        val hasVideos = current.videos.isNotEmpty()
        binding.videoScroll.isVisible = style == Settings.IMAGE_SMALL && hasVideos
        binding.videoColumn.isVisible = style != Settings.IMAGE_SMALL && hasVideos
        if (!hasVideos) return
        val metrics = resources.displayMetrics
        val density = metrics.density
        fun dp(value: Int) = (value * density).toInt()
        when (style) {
            Settings.IMAGE_MEDIUM -> {
                // 中图模式：视频卡片与图片卡片同尺寸、同圆角，纵向排列。
                val width = (metrics.widthPixels - 50 * density).toInt()
                val height = width * 3 / 4 - dp(44)
                current.videos.forEach { path ->
                    binding.videoColumn.addView(videoCard(path, width, height, 10))
                }
            }

            Settings.IMAGE_LARGE -> {
                // 大图模式：视频铺满正文宽度，16:9 圆角卡片。
                val width = (metrics.widthPixels - 48 * density).toInt()
                current.videos.forEach { path ->
                    binding.videoColumn.addView(videoCard(path, width, width * 9 / 16, 14))
                }
            }

            else -> {
                // 小图模式：横排 4:3 小缩略图。
                val width = dp(120)
                val height = width * 4 / 3
                val gap = dp(8)
                current.videos.forEach { path ->
                    binding.videoStrip.addView(
                        videoCard(path, width, height, 8).apply {
                            (layoutParams as ViewGroup.MarginLayoutParams).rightMargin = gap
                        }
                    )
                }
            }
        }
    }

    /** 生成一张带播放角标的视频封面卡片，圆角与对应图片模式一致。 */
    private fun videoCard(path: String, widthPx: Int, heightPx: Int, radiusDp: Int): FrameLayout {
        val density = resources.displayMetrics.density
        val box = FrameLayout(this).apply {
            layoutParams = ViewGroup.MarginLayoutParams(widthPx, heightPx)
            background = GradientDrawable().apply { cornerRadius = radiusDp * density }
            clipToOutline = true
        }
        val thumb = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(VideoLoader.thumbnail(path, widthPx))
        }
        val play = ImageView(this).apply {
            val size = (40 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            background = ContextCompat.getDrawable(context, R.drawable.bg_play_badge)
            setImageResource(R.drawable.ic_play)
            setPadding(
                (10 * density).toInt(),
                (10 * density).toInt(),
                (10 * density).toInt(),
                (10 * density).toInt()
            )
            contentDescription = getString(R.string.play_video)
        }
        box.addView(thumb)
        box.addView(play)
        box.setOnClickListener { openVideo(path) }
        return box
    }

    /** 全屏播放视频：系统播放控制条，点击视频外的区域关闭。 */
    private fun openVideo(path: String) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val box = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val video = VideoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }
        box.addView(video)
        box.setOnClickListener { dialog.dismiss() }
        val controller = MediaController(this)
        controller.setAnchorView(video)
        video.setMediaController(controller)
        video.setVideoPath(path)
        video.setOnPreparedListener { video.start() }
        dialog.setContentView(box)
        dialog.setOnDismissListener {
            video.stopPlayback()
            exitImmersiveFullscreen()
        }
        applyFullscreenDialog(dialog)
        dialog.show()
    }

    /** 页码圆点：当前页深色，其余浅灰；只有一张时不显示。 */
    private fun renderDots(count: Int, selected: Int) {
        binding.imageDots.removeAllViews()
        binding.imageDots.isVisible = count > 1
        if (count <= 1) return
        val density = resources.displayMetrics.density
        val size = (6 * density).toInt()
        val margin = (3 * density).toInt()
        val active = ContextCompat.getColor(this, R.color.text_primary)
        val inactive = 0xFFDDDDDD.toInt()
        for (i in 0 until count) {
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = margin
                    marginEnd = margin
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (i == selected) active else inactive)
                }
            }
            binding.imageDots.addView(dot)
        }
    }

    /** 横向分页里离屏幕中线最近的那一页。 */
    private fun nearestImagePosition(): Int {
        val pager = binding.imagePager
        if (pager.childCount == 0 || pager.width <= 0) return pagerDotPosition
        val center = pager.width / 2
        var best = 0
        var bestDistance = Int.MAX_VALUE
        for (i in 0 until pager.childCount) {
            val child = pager.getChildAt(i)
            val position = pager.getChildAdapterPosition(child)
            if (position < 0) continue
            val middle = (child.left + child.right) / 2
            val distance = abs(middle - center)
            if (distance < bestDistance) {
                bestDistance = distance
                best = position
            }
        }
        return best
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
        if (flipping || jumping) return
        val target = index + delta
        if (target !in ids.indices) {
            if (randomReview) {
                val message = if (delta < 0) R.string.random_review_first else R.string.random_review_last
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
            return
        }
        // 随机回顾里切换笔记时用保存同款提示音。
        if (randomReview) Feedback.saved(this)
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

    /** 触摸起点是否落在图片或视频区域上（小图 / 中图翻页 / 大图）。 */
    private fun isInImageStrip(event: MotionEvent): Boolean {
        val target = when {
            binding.imagePagerBox.isVisible -> binding.imagePagerBox
            binding.imageScroll.isVisible -> binding.imageScroll
            binding.imageColumn.isVisible -> binding.imageColumn
            binding.videoScroll.isVisible -> binding.videoScroll
            binding.videoColumn.isVisible -> binding.videoColumn
            else -> return false
        }
        val location = IntArray(2)
        target.getLocationOnScreen(location)
        val x = event.rawX
        val y = event.rawY
        return x >= location[0] && x <= location[0] + target.width &&
            y >= location[1] && y <= location[1] + target.height
    }

    private fun openEditor(cursor: Int = -1) {
        entry?.let {
            startActivity(
                EditDiaryActivity.intent(this, it.id, cursor = cursor)
                    // 附带浏览上下文，编辑保存后回到当前查看位置。
                    .putStringArrayListExtra(EditDiaryActivity.EXTRA_FROM_IDS, ArrayList(ids))
                    .putExtra(EditDiaryActivity.EXTRA_FROM_INDEX, index)
                    .putExtra(EditDiaryActivity.EXTRA_FROM_RANDOM, randomReview)
            )
        }
    }

    /** 全屏查看图片：同一篇笔记有多张图时可左右滑动切换，点任意位置关闭。 */
    private fun openImage(path: String) {
        val images = entry?.images?.takeIf { it.isNotEmpty() } ?: listOf(path)
        val start = images.indexOf(path).coerceAtLeast(0)
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val screenSize = maxOf(
            resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels
        )
        val density = resources.displayMetrics.density

        val pager = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ViewDiaryActivity, RecyclerView.HORIZONTAL, false)
            PagerSnapHelper().attachToRecyclerView(this)
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(Color.BLACK)
        }

        fun pagePosition(): Int {
            val lm = pager.layoutManager as? LinearLayoutManager ?: return 0
            val center = (pager.left + pager.right) / 2
            var best = 0
            var bestDistance = Int.MAX_VALUE
            for (i in lm.findFirstVisibleItemPosition()..lm.findLastVisibleItemPosition()) {
                val view = lm.findViewByPosition(i) ?: continue
                val distance = abs((view.left + view.right) / 2 - center)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = i
                }
            }
            return best
        }

        val counter = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            text = "${start + 1}/${images.size}"
            visibility = if (images.size > 1) View.VISIBLE else View.GONE
        }
        pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (images.size <= 1) return
                counter.text = "${pagePosition() + 1}/${images.size}"
            }
        })

        pager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount(): Int = images.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val image = ImageView(this@ViewDiaryActivity).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(Color.BLACK)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setOnClickListener { dialog.dismiss() }
                }
                return object : RecyclerView.ViewHolder(image) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val image = holder.itemView as ImageView
                val imagePath = images[position]
                image.setImageBitmap(
                    ImageLoader.load(imagePath, screenSize) ?: BitmapFactory.decodeFile(imagePath)
                )
            }
        }

        val content = FrameLayout(this).apply { addView(pager) }
        content.addView(
            counter,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = (40 * density).toInt() }
        )
        dialog.setContentView(content)
        dialog.setOnDismissListener { exitImmersiveFullscreen() }
        applyFullscreenDialog(dialog)
        dialog.show()
        pager.scrollToPosition(start)
    }

    /** 全屏黑底弹窗：铺满整屏并隐藏系统栏，图片/视频真正占满整个屏幕。 */
    @Suppress("DEPRECATION")
    private fun applyFullscreenDialog(dialog: Dialog) {
        // Dialog 是子窗口，盖不住查看页自己的浅色状态栏，先让本页进入沉浸式全屏。
        enterImmersiveFullscreen()
        val window = dialog.window ?: return
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        window.decorView.setBackgroundColor(Color.BLACK)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        // 隐藏状态栏：旧系统靠窗口标记，新系统也认这个标记。
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        // 经典沉浸式标志位，各版本与定制 ROM 都能生效。
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        dialog.setOnShowListener {
            window.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            // 隐藏状态栏与导航栏，避免顶部留一条白色状态栏；
            // 从屏幕边缘上滑仍可临时唤出系统栏。
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    /** 进入全屏查看时隐藏系统栏，让界面真正铺满整屏。 */
    @Suppress("DEPRECATION")
    private fun enterImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    /** 关闭全屏查看后恢复系统栏，状态栏回到主题的浅色背景与深色图标。 */
    @Suppress("DEPRECATION")
    private fun exitImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            show(WindowInsetsCompat.Type.systemBars())
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = false
        }
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun showMenu() {
        val dialog = Dialog(this)
        val menu = DialogNoteMenuBinding.inflate(layoutInflater)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(menu.root)

        menu.itemEdit.setOnClickListener {
            dialog.dismiss()
            entry?.let {
                startActivity(
                    EditDiaryActivity.intent(this, it.id)
                        .putStringArrayListExtra(EditDiaryActivity.EXTRA_FROM_IDS, ArrayList(ids))
                        .putExtra(EditDiaryActivity.EXTRA_FROM_INDEX, index)
                        .putExtra(EditDiaryActivity.EXTRA_FROM_RANDOM, randomReview)
                )
            }
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

    /** 笔记还没有坐标：把当前位置和地点名单独一行记到笔记末尾，之后点定位图标就能打开地图。 */
    private fun recordLocation(lat: Double, lng: Double) {
        GeoName.resolve(this, lat, lng) { name ->
            if (isFinishing || isDestroyed) return@resolve
            val current = entry ?: return@resolve
            val coordinate = GeoPoint.recordLine(this, name, lat, lng)
            val content = current.content.trimEnd()
            val updated = current.copy(
                content = if (content.isEmpty()) coordinate else "$content\n$coordinate",
                updatedAt = System.currentTimeMillis()
            )
            store.save(updated)
            entry = updated
            render()
            Toast.makeText(this, R.string.location_inserted, Toast.LENGTH_SHORT).show()
        }
    }

    /** 笔记还没有坐标：取当前位置后记到笔记里。 */
    @SuppressLint("MissingPermission")
    private fun fetchCurrentLocation() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            requestLocation.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }

        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = buildList {
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                add(LocationManager.GPS_PROVIDER)
            }
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
        }
        if (providers.isEmpty()) {
            Toast.makeText(this, R.string.location_failed, Toast.LENGTH_SHORT).show()
            return
        }

        var lastKnown: Location? = null
        providers.forEach { provider ->
            val location = runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
                ?: return@forEach
            val current = lastKnown
            if (current == null || location.time > current.time) lastKnown = location
        }
        lastKnown?.let {
            recordLocation(it.latitude, it.longitude)
            return
        }

        if (locationWaiting) return
        locationWaiting = true
        Toast.makeText(this, R.string.location_fetching, Toast.LENGTH_SHORT).show()

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!locationWaiting) return
                locationWaiting = false
                manager.removeUpdates(this)
                recordLocation(location.latitude, location.longitude)
            }

            override fun onProviderEnabled(provider: String) {}

            override fun onProviderDisabled(provider: String) {}

            @Suppress("DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        runCatching {
            manager.requestLocationUpdates(
                providers.first(),
                0L,
                0f,
                listener,
                Looper.getMainLooper()
            )
            Handler(Looper.getMainLooper()).postDelayed({
                if (!locationWaiting) return@postDelayed
                locationWaiting = false
                manager.removeUpdates(listener)
                Toast.makeText(this, R.string.location_failed, Toast.LENGTH_SHORT).show()
            }, 15000L)
        }.onFailure {
            locationWaiting = false
            Toast.makeText(this, R.string.location_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** 查看页里直接给当前笔记加/删标签，保存后立即刷新标签行。 */
    private fun editTags() {
        val current = entry ?: return
        TagEditor.show(this, current.tags) { updated ->
            val updatedEntry = entry?.copy(
                tags = updated,
                updatedAt = System.currentTimeMillis()
            ) ?: return@show
            store.save(updatedEntry)
            entry = updatedEntry
            render()
        }
    }


    private fun confirmDelete() {
        val current = entry ?: return
        showDeleteConfirm(this) {
            Feedback.deleted(this)
            // 随机回顾里删除后继续看下一篇；删的是最后一篇则回到上一篇。
            val remaining = ids.filterNot { it == current.id }
            val leaving = !randomReview || remaining.isEmpty()
            // 页面马上要关闭：先标记，收到的数据变更广播就不会再触发读盘。
            closing = leaving
            if (leaving) {
                // 先退出页面，删除放到后台线程，避免点击确认时卡顿。
                finish()
                DiaryStore.notifyDeleted(this, current.id)
                Thread { store.trash(current.id) }.start()
                return@showDeleteConfirm
            }
            DiaryStore.notifyDeleted(this, current.id)
            ids = remaining
            index = index.coerceAtMost(remaining.lastIndex)
            val nextId = ids[index]
            // 删除与读取下一篇在同一个后台线程顺序执行，避免读写竞争，也不阻塞界面。
            Thread {
                store.trash(current.id)
                val next = store.load().find { it.id == nextId }
                runOnUiThread {
                    entry = next
                    if (next == null) finish() else render()
                    binding.scrollContent.scrollTo(0, 0)
                }
            }.start()
        }
    }
}
