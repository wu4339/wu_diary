package com.wu.diary

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewDiaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = DiaryStore(this)
        ids = intent.getStringArrayListExtra(EXTRA_IDS) ?: emptyList()
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (ids.size - 1).coerceAtLeast(0))

        val scale = Settings(this).fontScale
        binding.textDay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f * scale)
        binding.textYear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * scale)
        binding.textContent.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f * scale)

        binding.btnClose.setOnClickListener { finish() }
        binding.btnMore.setOnClickListener { showMenu() }
        binding.btnPrev.setOnClickListener { step(-1) }
        binding.btnNext.setOnClickListener { step(1) }
        binding.textTitle.isVisible = intent.getBooleanExtra(EXTRA_RANDOM_REVIEW, false)
        binding.scrollContent.setOnTouchListener { _, event ->
            doubleTapDetector.onTouchEvent(event)
            false
        }
    }

    /** 浏览页双击进入编辑（可在设置中开启）。 */
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

        val text = listOf(current.title, current.content)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        binding.textContent.text = text
        binding.textContent.isVisible = text.isNotEmpty()

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

    private fun step(delta: Int) {
        val target = index + delta
        if (target !in ids.indices) return
        index = target
        load()
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
        menu.itemDelete.setOnClickListener {
            dialog.dismiss()
            confirmDelete()
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
