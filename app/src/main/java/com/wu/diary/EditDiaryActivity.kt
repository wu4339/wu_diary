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
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.wu.diary.data.DiaryStore
import com.wu.diary.data.Settings
import com.wu.diary.databinding.ActivityEditDiaryBinding
import com.wu.diary.model.DiaryEntry
import com.wu.diary.util.Feedback
import com.wu.diary.util.GeoName
import com.wu.diary.util.GeoPoint
import com.wu.diary.util.ImageLoader
import com.wu.diary.util.TagEditor
import com.wu.diary.util.TagChips
import com.wu.diary.util.VideoLoader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class EditDiaryActivity : BaseActivity() {

    companion object {
        private const val EXTRA_ID = "entry_id"
        private const val EXTRA_DATE = "entry_date"
        private const val EXTRA_CURSOR = "entry_cursor"

        /** 查看页打开编辑时附带的浏览上下文，保存后按原样回到查看页。 */
        const val EXTRA_FROM_IDS = "from_view_ids"
        const val EXTRA_FROM_INDEX = "from_view_index"
        const val EXTRA_FROM_RANDOM = "from_view_random"

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
    private val videoPaths = mutableListOf<String>()
    private val tags = mutableListOf<String>()
    private var locationWaiting = false

    /** 编辑页字号缩放，用于标签色块。 */
    private var tagScale = 1f

    private val pickImages =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            uris.forEach { uri ->
                if (imagePaths.size < MAX_IMAGES) {
                    importImage(uri)?.let { imagePaths.add(it) }
                }
            }
            renderImages()
        }

    private val pickVideo =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { importVideo(it)?.let { path -> videoPaths.add(path) } }
            renderVideos()
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
                videoPaths.addAll(it.videos)
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
        val scale = settings.readingScale
        tagScale = scale
        binding.textEditorDay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f * scale)
        binding.textEditorYear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * scale)
        binding.editContent.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            settings.readingFontSp.toFloat()
        )
        binding.editContent.typeface = settings.typeface
        binding.editContent.setLineSpacing(
            settings.readingLineSpacing * resources.displayMetrics.density,
            1f
        )

        renderImages()
        renderVideos()
        renderEditorTags()
        setupCursorVisibility()

        binding.btnClose.setOnClickListener { finish() }
        // 快捷输入界面的 home 键：直接回到 App 主界面。
        binding.btnHome.setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
        binding.btnSave.setOnClickListener { save() }
        binding.btnImage.setOnClickListener { pickImages.launch("image/*") }
        // 已有坐标就打开地图；还没有坐标时先插入当前位置，插入后再点才打开地图。
        binding.btnEditorGeo.setOnClickListener {
            val match = GeoPoint.findAll(binding.editContent.text?.toString().orEmpty()).firstOrNull()
            if (match != null) GeoPoint.openMap(this, match.lat, match.lng) else fetchLocation()
        }
        binding.btnVideo.setOnClickListener { pickVideo.launch("video/*") }
        binding.btnTag.setOnClickListener { editTags() }
        binding.btnEditorTags.setOnClickListener { editTags() }
        binding.btnLocation.setOnClickListener { fetchLocation() }
        binding.btnCut.setOnClickListener { cutParagraph() }
        binding.btnList.setOnClickListener { toggleListMarker() }
        setupListContinuation()

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

    /** 日期行下面的操作行：定位图标固定显示；有标签后标签图标隐藏，位置换成标签色块。 */
    private fun renderEditorTags() {
        TagChips.render(binding.tagStrip, tags, tagScale, onClick = { editTags() }, tile = true)
        binding.tagStrip.isVisible = tags.isNotEmpty()
        binding.btnEditorTags.isVisible = tags.isEmpty()
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

    /** 编辑页的视频缩略图条：封面帧 + 播放角标 + 删除按钮。 */
    private fun renderVideos() {
        binding.videoScroll.isVisible = videoPaths.isNotEmpty()
        binding.videoStrip.removeAllViews()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val width = dp(120)
        val height = dp(68)
        val gap = dp(8)
        videoPaths.toList().forEach { path ->
            val thumb = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(width, height)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(VideoLoader.thumbnail(path, width))
            }
            val play = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)
                background = ContextCompat.getDrawable(context, R.drawable.bg_play_badge)
                setImageResource(R.drawable.ic_play)
                contentDescription = getString(R.string.play_video)
            }
            val remove = ImageButton(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    dp(22),
                    dp(22)
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = dp(4)
                    rightMargin = dp(4)
                }
                background = ContextCompat.getDrawable(context, R.drawable.bg_x_badge)
                setImageResource(R.drawable.ic_close)
                imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.text_primary))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(5), dp(5), dp(5), dp(5))
                contentDescription = getString(R.string.remove_video)
                setOnClickListener { removeVideo(path) }
            }
            val cell = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(width, height).apply { rightMargin = gap }
                addView(thumb)
                addView(play)
                addView(remove)
            }
            binding.videoStrip.addView(cell)
        }
    }

    /** 点击角标移除视频；如果是这次新导入的文件，顺带删掉。 */
    private fun removeVideo(path: String) {
        videoPaths.remove(path)
        if (existing?.videos?.contains(path) != true) File(path).delete()
        renderVideos()
    }

    private fun importVideo(uri: Uri): String? = runCatching {
        val dir = File(filesDir, "videos").apply { mkdirs() }
        val ext = runCatching {
            contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.substringAfterLast('.', "")
                        ?.takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) }
                } else null
            }
        }.getOrNull() ?: "mp4"
        val target = File(dir, "${UUID.randomUUID()}.$ext")
        contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        target.absolutePath
    }.getOrNull()

    /** 编辑标签：可直接输入，也可以点选已有的标签；空格/逗号分隔，最多 10 个。 */
    private fun editTags() {
        TagEditor.show(this, tags) { updated ->
            tags.clear()
            tags.addAll(updated)
            renderEditorTags()
        }
    }


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
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("wu_diary", paragraph))
        val deleteEnd = if (lineEnd == -1) text.length else lineEnd + 1
        text.delete(lineStart, deleteEnd)
        edit.setSelection(lineStart)
    }

    /** 给光标所在行加上或去掉无序列表符号。 */
    private fun toggleListMarker() {
        val edit = binding.editContent
        val text = edit.text
        val cursor = edit.selectionStart.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', cursor - 1) + 1
        if (text.startsWith("- ", lineStart)) {
            text.delete(lineStart, lineStart + 2)
            edit.setSelection((cursor - 2).coerceAtLeast(lineStart))
        } else {
            text.insert(lineStart, "- ")
            edit.setSelection((cursor + 2).coerceAtMost(text.length))
        }
    }

    /** 无序列表行回车后自动续写符号；空列表项回车则结束列表。 */
    private fun setupListContinuation() {
        var insertedNewline = false
        binding.editContent.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, after: Int) {
                insertedNewline =
                    before == 0 && after == 1 && start > 0 && s?.get(start) == '\n'
            }

            override fun afterTextChanged(s: Editable?) {
                if (!insertedNewline || s == null) return
                insertedNewline = false
                val cursor = binding.editContent.selectionStart
                if (cursor <= 1) return
                val prevStart = s.lastIndexOf('\n', cursor - 2) + 1
                val prevLine = s.subSequence(prevStart, cursor - 1).toString()
                val match = listLine.matchEntire(prevLine) ?: return
                // 上一条是空列表项（只有 "- "）时不再续写，相当于结束列表。
                if (match.groupValues[3].isBlank()) return
                val marker = "${match.groupValues[1]}${match.groupValues[2]} "
                s.insert(cursor, marker)
                binding.editContent.setSelection(cursor + marker.length)
            }
        })
    }

    private val listLine = Regex("^(\\s*)([-*+])\\s+(.*)$")

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
        GeoName.resolve(this, location.latitude, location.longitude) { name ->
            if (isFinishing || isDestroyed) return@resolve
            val line = GeoPoint.recordLine(this, name, location.latitude, location.longitude)
            val edit = binding.editContent
            val text = edit.text
            val start = edit.selectionStart.coerceIn(0, text.length)
            val end = edit.selectionEnd.coerceIn(0, text.length)
            val from = minOf(start, end)
            val to = maxOf(start, end)
            // 位置记录单独占一行：光标前后不是换行的地方补上换行。
            val before = if (from > 0 && text[from - 1] != '\n') "\n" else ""
            val after = if (to < text.length && text[to] != '\n') "\n" else ""
            val insert = before + line + after
            text.replace(from, to, insert)
            edit.setSelection(from + insert.length)
            Toast.makeText(this, R.string.location_inserted, Toast.LENGTH_SHORT).show()
        }
    }

    private fun save() {
        val content = binding.editContent.text.toString().trim()
        if (content.isEmpty() && imagePaths.isEmpty() && videoPaths.isEmpty()) {
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
            videos = videoPaths.toList(),
            tags = tags.toList()
        )
            ?: DiaryEntry(
                title = "",
                content = content,
                createdAt = entryDate,
                updatedAt = entryDate,
                images = imagePaths.toList(),
                videos = videoPaths.toList(),
                tags = tags.toList()
            )
        // 立即关闭编辑页，存储放到后台线程，避免点击保存后界面停顿。
        Feedback.saved(this)
        if (existing != null) {
            // 编辑已有笔记：保存后退出到查看界面。
            // 从随机回顾进入时带上原浏览列表与位置，其他情况只展示当前笔记。
            val fromIds = intent.getStringArrayListExtra(EXTRA_FROM_IDS)
            val viewIntent = if (fromIds != null) {
                ViewDiaryActivity.intent(
                    this,
                    fromIds,
                    intent.getIntExtra(EXTRA_FROM_INDEX, 0),
                    intent.getBooleanExtra(EXTRA_FROM_RANDOM, false)
                )
            } else {
                ViewDiaryActivity.intent(this, listOf(entry.id), 0)
            }.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // 带上最新内容，查看页首帧即渲染新内容，避免旧内容闪现。
            viewIntent.putExtra(ViewDiaryActivity.EXTRA_FOCUS_ENTRY, entry.toJson().toString())
            startActivity(viewIntent)
            // 保存后直接去查看页：只播放「打开查看页」的进入动画，不再播放关闭动画。
            skipCloseTransitionOnce()
        }
        finish()
        // 页面已关闭，用 Application Context 写入，避免继续持有正在销毁的 Activity。
        val appContext = applicationContext
        Thread { DiaryStore(appContext).save(entry) }.start()
    }
}
