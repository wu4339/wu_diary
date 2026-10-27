package com.wu.diary

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import com.wu.diary.data.DiaryStore
import com.wu.diary.data.Settings
import com.wu.diary.databinding.ActivityEditDiaryBinding
import com.wu.diary.model.DiaryEntry
import com.wu.diary.util.Feedback
import com.wu.diary.util.ImageLoader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class EditDiaryActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_ID = "entry_id"
        private const val EXTRA_DATE = "entry_date"
        private const val EXTRA_CURSOR = "entry_cursor"
        private const val MAX_IMAGES = 9

        fun intent(
            context: Context,
            entryId: String?,
            date: Long? = null,
            cursor: Int = -1
        ): Intent =
            Intent(context, EditDiaryActivity::class.java)
                .putExtra(EXTRA_ID, entryId)
                .putExtra(EXTRA_DATE, date ?: 0L)
                .putExtra(EXTRA_CURSOR, cursor)
    }

    private lateinit var binding: ActivityEditDiaryBinding
    private lateinit var store: DiaryStore
    private var existing: DiaryEntry? = null
    private var presetDate: Long = 0L
    /** 从查看页双击进入时，光标落在双击的位置；-1 表示落在末尾。 */
    private var initialCursor = -1
    private val imagePaths = mutableListOf<String>()
    private val tags = mutableListOf<String>()
    private var locationWaiting = false

    private val pickImages =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            uris.forEach { uri ->
                if (imagePaths.size < MAX_IMAGES) {
                    importImage(uri)?.let { imagePaths.add(it) }
                }
            }
            renderImages()
        }

    private val requestLocation =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.any { it }) {
                fetchLocation()
            } else {
                Toast.makeText(this, R.string.location_failed, Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditDiaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = DiaryStore(this)
        presetDate = intent.getLongExtra(EXTRA_DATE, 0L)
        initialCursor = intent.getIntExtra(EXTRA_CURSOR, -1)
        intent.getStringExtra(EXTRA_ID)?.let { id ->
            existing = store.load().find { it.id == id }
            existing?.let {
                imagePaths.addAll(it.images)
                tags.addAll(it.tags)
                binding.editContent.setText(
                    listOf(it.title, it.content).filter { part -> part.isNotBlank() }
                        .joinToString("\n")
                )
            }
        }

        val date = Date(existing?.createdAt ?: presetDate.takeIf { it > 0L } ?: System.currentTimeMillis())
        binding.textEditorDay.text = SimpleDateFormat("M/d", Locale.getDefault()).format(date)
        binding.textEditorYear.text = SimpleDateFormat("yyyy", Locale.getDefault()).format(date)

        val settings = Settings(this)
        val scale = settings.fontScale
        binding.textEditorDay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f * scale)
        binding.textEditorYear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * scale)
        binding.editContent.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f * scale)
        binding.editContent.setLineSpacing(settings.lineSpacing * resources.displayMetrics.density, 1f)

        renderImages()
        renderEditorTags()
        setupCursorVisibility()

        binding.btnClose.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener { save() }
        binding.btnImage.setOnClickListener { pickImages.launch("image/*") }
        binding.btnTag.setOnClickListener { editTags() }
        binding.btnLocation.setOnClickListener { fetchLocation() }
        binding.btnCut.setOnClickListener { cutParagraph() }

        if (existing != null) {
            binding.editContent.setSelection(binding.editContent.text.length)
        }
        startTyping()
    }

    /** 进入编辑页即输入：聚焦文本框、光标落在上次的位置（或末尾）并弹出键盘。 */
    private fun startTyping() {
        binding.editContent.requestFocus()
        binding.editContent.post {
            val text = binding.editContent.text
            val length = text?.length ?: 0
            val position = if (initialCursor in 0..length) initialCursor else length
            binding.editContent.setSelection(position)
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.editContent, InputMethodManager.SHOW_IMPLICIT)
            // 从浏览页双击进入时把光标行放到屏幕中间；新建笔记仍只保证光标可见。
            binding.editContent.postDelayed({
                if (initialCursor >= 0) centerCursorLine() else scrollCursorIntoView()
            }, 350L)
            if (initialCursor >= 0) {
                // 键盘弹出会改变可见高度，等布局稳定后再校正一次。
                binding.editContent.postDelayed({ centerCursorLine() }, 700L)
            }
        }
    }

    /** 把光标所在行滚动到可见区域的正中。 */
    private fun centerCursorLine() {
        val edit = binding.editContent
        val layout = edit.layout ?: return
        val offset = edit.selectionEnd.coerceIn(0, edit.text?.length ?: 0)
        val line = layout.getLineForOffset(offset)
        val lineCenter = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2

        var top = edit.top
        var parent = edit.parent as? View
        while (parent != null && parent !== binding.scrollContent) {
            top += parent.top
            parent = parent.parent as? View
        }
        val target = top + edit.paddingTop + lineCenter - binding.scrollContent.height / 2
        binding.scrollContent.smoothScrollTo(0, target.coerceAtLeast(0))
    }

    /** 编辑页整体可滚动，输入或键盘弹出时把光标所在行带回可见区域。 */
    private fun setupCursorVisibility() {
        binding.editContent.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                binding.editContent.post { scrollCursorIntoView() }
            }
        })
        binding.editContent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            scrollCursorIntoView()
        }
        // 键盘弹出 / 收起时可视区域变化，同样把光标行带回可见位置。
        binding.scrollContent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            scrollCursorIntoView()
        }
    }

    private fun scrollCursorIntoView() {
        val edit = binding.editContent
        val layout = edit.layout ?: return
        val offset = edit.selectionEnd.coerceIn(0, edit.text?.length ?: 0)
        val line = layout.getLineForOffset(offset)
        val extra = (64 * resources.displayMetrics.density).toInt()
        val rect = Rect(
            edit.paddingLeft,
            layout.getLineTop(line) + edit.paddingTop,
            edit.width - edit.paddingRight,
            layout.getLineBottom(line) + edit.paddingTop + extra
        )
        binding.scrollContent.requestChildRectangleOnScreen(edit, rect, false)
    }

    /** 编辑页展示当前笔记已选的标签。 */
    private fun renderEditorTags() {
        binding.textEditorTags.text = tags.joinToString("  ") { "#$it" }
        // 标签行始终占位，保存标签后正文不会被挤下去。
        binding.textEditorTags.isInvisible = tags.isEmpty()
    }

    private fun renderImages() {
        binding.imageScroll.isVisible = imagePaths.isNotEmpty()
        binding.imageStrip.removeAllViews()
        val density = resources.displayMetrics.density
        val size = (84 * density).toInt()
        val gap = (8 * density).toInt()
        imagePaths.toList().forEach { path ->
            val thumb = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(size, size)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(ImageLoader.load(path, size))
            }
            val remove = ImageButton(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    (22 * density).toInt(),
                    (22 * density).toInt()
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = (4 * density).toInt()
                    rightMargin = (4 * density).toInt()
                }
                background = ContextCompat.getDrawable(context, R.drawable.bg_x_badge)
                setImageResource(R.drawable.ic_close)
                imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.text_primary))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding((5 * density).toInt(), (5 * density).toInt(), (5 * density).toInt(), (5 * density).toInt())
                contentDescription = getString(R.string.remove_image)
                setOnClickListener { removeImage(path) }
            }
            val cell = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply { rightMargin = gap }
                addView(thumb)
                addView(remove)
            }
            binding.imageStrip.addView(cell)
        }
    }

    /** 点击缩略图移除；如果是这次新导入的图片，顺带删掉文件。 */
    private fun removeImage(path: String) {
        imagePaths.remove(path)
        if (existing?.images?.contains(path) != true) File(path).delete()
        renderImages()
    }

    private fun importImage(uri: Uri): String? = runCatching {
        val dir = File(filesDir, "images").apply { mkdirs() }
        val target = File(dir, "${UUID.randomUUID()}.jpg")
        contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        target.absolutePath
    }.getOrNull()

    /** 编辑标签：可直接输入，也可以点选已有的标签；空格/逗号分隔，最多 10 个。 */
    private fun editTags() {
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
            setText(tags.joinToString(" "))
            hint = getString(R.string.tags_hint)
            setSingleLine(true)
            setPadding(dp(4), dp(10), dp(4), dp(10))
        }
        container.addView(input)

        if (existingTags.isNotEmpty()) {
            container.addView(
                TextView(this).apply {
                    text = getString(R.string.existing_tags)
                    setTextColor(ContextCompat.getColor(this@EditDiaryActivity, R.color.text_secondary))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, dp(16), 0, dp(6))
                }
            )
            val chipRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(8))
            }
            val chipViews = mutableListOf<Pair<String, TextView>>()

            fun refreshChips() {
                val current = parseTags(input.text.toString())
                chipViews.forEach { (tag, view) ->
                    val selected = tag in current
                    view.setTextColor(
                        ContextCompat.getColor(
                            this@EditDiaryActivity,
                            if (selected) R.color.text_primary else R.color.text_secondary
                        )
                    )
                    view.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                }
            }

            existingTags.forEach { tag ->
                val chip = TextView(this).apply {
                    text = "#$tag"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    isClickable = true
                    setOnClickListener {
                        val current = parseTags(input.text.toString()).toMutableList()
                        if (tag in current) current.remove(tag) else current.add(tag)
                        input.setText(current.joinToString(" "))
                        input.setSelection(input.text.length)
                        refreshChips()
                    }
                }
                chipRow.addView(chip)
                chipViews.add(tag to chip)
            }
            container.addView(
                HorizontalScrollView(this).apply {
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    addView(chipRow)
                }
            )
            refreshChips()
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.tag_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                tags.clear()
                tags.addAll(parseTags(input.text.toString()))
                renderEditorTags()
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

    /** 剪切光标所在的整个段落：复制到剪贴板并从正文中删除。 */
    private fun cutParagraph() {
        val edit = binding.editContent
        val cursor = edit.selectionStart.coerceIn(0, edit.text.length)
        val text = edit.text
        val lineStart = text.lastIndexOf("\n", cursor - 1) + 1
        val lineEnd = text.indexOf("\n", cursor)
        val end = if (lineEnd == -1) text.length else lineEnd
        val paragraph = text.subSequence(lineStart, end).toString()
        if (paragraph.isBlank()) {
            // 空行：直接删掉这一行（连同行尾换行），光标留在原位。
            if (lineEnd != -1) {
                text.delete(lineStart, lineEnd + 1)
                edit.setSelection(lineStart.coerceIn(0, text.length))
            } else if (lineStart > 0) {
                text.delete(lineStart - 1, lineStart)
                edit.setSelection((lineStart - 1).coerceIn(0, text.length))
            }
            Toast.makeText(this, R.string.blank_line_removed, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("wu_diary", paragraph))
        val deleteEnd = if (lineEnd == -1) text.length else lineEnd + 1
        text.delete(lineStart, deleteEnd)
        edit.setSelection(lineStart)
        Toast.makeText(this, R.string.paragraph_cut, Toast.LENGTH_SHORT).show()
    }

    /** 获取当前位置坐标并插入到光标处。 */
    @SuppressLint("MissingPermission")
    private fun fetchLocation() {
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
            insertLocation(it)
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
                insertLocation(location)
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

    private fun insertLocation(location: Location) {
        val coordinates = String.format(Locale.US, "%.6f, %.6f", location.latitude, location.longitude)
        val edit = binding.editContent
        val start = edit.selectionStart.coerceIn(0, edit.text.length)
        val end = edit.selectionEnd.coerceIn(0, edit.text.length)
        val from = minOf(start, end)
        val to = maxOf(start, end)
        edit.text.replace(from, to, coordinates)
        edit.setSelection(from + coordinates.length)
        Toast.makeText(this, R.string.location_inserted, Toast.LENGTH_SHORT).show()
    }

    private fun save() {
        val content = binding.editContent.text.toString().trim()
        if (content.isEmpty() && imagePaths.isEmpty()) {
            finish()
            return
        }
        val now = System.currentTimeMillis()
        val entryDate = presetDate.takeIf { it > 0L } ?: now
        val entry = existing?.copy(
            title = "",
            content = content,
            updatedAt = now,
            images = imagePaths.toList(),
            tags = tags.toList()
        )
            ?: DiaryEntry(
                title = "",
                content = content,
                createdAt = entryDate,
                updatedAt = entryDate,
                images = imagePaths.toList(),
                tags = tags.toList()
            )
        // 立即关闭编辑页，存储放到后台线程，避免点击保存后界面停顿。
        Feedback.saved(this)
        finish()
        // 页面已关闭，用 Application Context 写入，避免继续持有正在销毁的 Activity。
        val appContext = applicationContext
        Thread { DiaryStore(appContext).save(entry) }.start()
    }
}
