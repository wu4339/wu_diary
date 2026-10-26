package com.wu.diary

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.wu.diary.data.DiaryStore
import com.wu.diary.data.Settings
import com.wu.diary.databinding.ActivityEditDiaryBinding
import com.wu.diary.model.DiaryEntry
import com.wu.diary.util.ImageLoader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class EditDiaryActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_ID = "entry_id"
        private const val MAX_IMAGES = 9

        fun intent(context: Context, entryId: String?): Intent =
            Intent(context, EditDiaryActivity::class.java).putExtra(EXTRA_ID, entryId)
    }

    private lateinit var binding: ActivityEditDiaryBinding
    private lateinit var store: DiaryStore
    private var existing: DiaryEntry? = null
    private val imagePaths = mutableListOf<String>()

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
        intent.getStringExtra(EXTRA_ID)?.let { id ->
            existing = store.load().find { it.id == id }
            existing?.let {
                imagePaths.addAll(it.images)
                binding.editContent.setText(
                    listOf(it.title, it.content).filter { part -> part.isNotBlank() }
                        .joinToString("\n")
                )
            }
        }

        val date = Date(existing?.createdAt ?: System.currentTimeMillis())
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

    private fun save() {
        val content = binding.editContent.text.toString().trim()
        if (content.isEmpty() && imagePaths.isEmpty()) {
            finish()
            return
        }
        val now = System.currentTimeMillis()
        val entry = existing?.copy(title = "", content = content, updatedAt = now, images = imagePaths.toList())
            ?: DiaryEntry(title = "", content = content, images = imagePaths.toList())
        store.save(entry)
        finish()
    }
}
