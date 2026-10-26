package com.wu.diary

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.method.LinkMovementMethod
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import com.wu.diary.data.DiaryStore
import com.wu.diary.data.Settings
import com.wu.diary.databinding.ActivityViewDiaryBinding
import com.wu.diary.databinding.DialogNoteMenuBinding
import com.wu.diary.model.DiaryEntry
import com.wu.diary.util.Feedback
import com.wu.diary.util.ImageLoader
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewDiaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = DiaryStore(this)
        ids = intent.getStringArrayListExtra(EXTRA_IDS) ?: emptyList()
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (ids.size - 1).coerceAtLeast(0))

        val settings = Settings(this)
        val scale = settings.fontScale
        binding.textDay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f * scale)
        binding.textYear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * scale)
        binding.textContent.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f * scale)
        binding.textTags.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * scale)
        binding.textContent.setLineSpacing(
            settings.lineSpacing * resources.displayMetrics.density,
            1f
        )
        binding.textContent.movementMethod = LinkMovementMethod.getInstance()

        binding.btnClose.setOnClickListener { finish() }
        binding.btnMore.setOnClickListener { showMenu() }
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
     * 整页范围识别左右滑动与双击：正文 TextView（可点击链接）和 ScrollView 都会消费触摸事件，
     * 因此在这里直接按横向拖动距离判断，不再依赖 fling 速度，普通浏览页也能滑动切换。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        doubleTapDetector.onTouchEvent(ev)
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
        return super.dispatchTouchEvent(ev)
    }

    /** 浏览页手势：双击进入编辑（可在设置中开启）。 */
    private val doubleTapDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (Settings(this@ViewDiaryActivity).doubleTapEdit) {
                    openEditor()
                    return true
                }
                return false
            }
        })
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
        binding.textContent.text = text
        binding.textContent.isVisible = text.isNotEmpty()

        binding.textTags.text = current.tags.joinToString("  ") { "#$it" }
        binding.textTags.isVisible = current.tags.isNotEmpty()

        renderImages(current)
        updateNav()
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

    private fun openEditor() {
        entry?.let { startActivity(EditDiaryActivity.intent(this, it.id)) }
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
            setImageBitmap(
                ImageLoader.load(path, screenSize) ?: BitmapFactory.decodeFile(path)
            )
        }
        image.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(image)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            statusBarColor = Color.BLACK
            navigationBarColor = Color.BLACK
            WindowInsetsControllerCompat(this, image).isAppearanceLightStatusBars = false
            WindowInsetsControllerCompat(this, image).isAppearanceLightNavigationBars = false
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

    private fun confirmDelete() {
        val current = entry ?: return
        AlertDialog.Builder(this)
            .setMessage(R.string.delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                store.delete(current.id)
                Feedback.deleted(this)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
