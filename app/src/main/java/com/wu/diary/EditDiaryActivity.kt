package com.wu.diary

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
        private const val MAX_IMAGES = 9

        fun intent(context: Context, entryId: String?, date: Long? = null): Intent =
            Intent(context, EditDiaryActivity::class.java)
                .putExtra(EXTRA_ID, entryId)
                .putExtra(EXTRA_DATE, date ?: 0L)
    }

    private lateinit var binding: ActivityEditDiaryBinding
    private lateinit var store: DiaryStore
    private var existing: DiaryEntry? = null
    private var presetDate: Long = 0L
    private val imagePaths = mutableListOf<String>()
    private val tags = mutableListOf<String>()

    private val pickImages =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            uris.forEach { uri ->
                if (imagePaths.size < MAX_IMAGES) {
                    importImage(uri)?.let { imagePaths.add(it) }
                }
            }
            renderImages()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditDiaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = DiaryStore(this)
        presetDate = intent.getLongExtra(EXTRA_DATE, 0L)
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

        val scale = Settings(this).fontScale
        binding.textEditorDay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f * scale)
        binding.textEditorYear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * scale)
        binding.editContent.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f * scale)

        renderImages()

        binding.btnClose.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener { save() }
        binding.btnImage.setOnClickListener { pickImages.launch("image/*") }
        binding.btnTag.setOnClickListener { editTags() }

        if (existing != null) {
            binding.editContent.setSelection(binding.editContent.text.length)
        }
        startTyping()
    }

    /** 进入编辑页即输入：聚焦文本框、光标落在末尾并弹出键盘。 */
    private fun startTyping() {
        binding.editContent.requestFocus()
        binding.editContent.post {
            val text = binding.editContent.text
            if (text != null) binding.editContent.setSelection(text.length)
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.editContent, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun renderImages() {
        binding.imageScroll.isVisible = imagePaths.isNotEmpty()
        binding.imageStrip.removeAllViews()
        val size = (84 * resources.displayMetrics.density).toInt()
        val gap = (8 * resources.displayMetrics.density).toInt()
        imagePaths.toList().forEach { path ->
            val image = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply { rightMargin = gap }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(ImageLoader.load(path, size))
            }
            image.setOnClickListener { removeImage(path) }
            binding.imageStrip.addView(image)
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

    /** 编辑标签：空格/逗号分隔，最多 10 个。 */
    private fun editTags() {
        val density = resources.displayMetrics.density
        val input = EditText(this).apply {
            setText(tags.joinToString(" "))
            hint = getString(R.string.tags_hint)
            setSingleLine(true)
            setPadding(
                (22 * density).toInt(),
                (10 * density).toInt(),
                (22 * density).toInt(),
                (10 * density).toInt()
            )
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.tag_dialog_title)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                tags.clear()
                tags.addAll(parseTags(input.text.toString()))
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
        store.save(entry)
        Feedback.saved(this)
        finish()
    }
}
