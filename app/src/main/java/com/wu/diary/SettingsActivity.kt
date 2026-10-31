package com.wu.diary

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.wu.diary.data.DiaryStore
import com.wu.diary.data.ExternalDiaryStore
import com.wu.diary.data.Settings
import com.wu.diary.databinding.ActivitySettingsBinding
import com.wu.diary.util.BackupManager

class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: Settings

    private val storageLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { changeStorage(it) }
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            uri?.let { exportNotes(it) }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importNotes(it) }
        }

    /** 字号选项统一按 sp 显示：12sp ~ 22sp。 */
    private val fontLabels: Array<String>
        get() = Settings.FONT_SPS.map { getString(R.string.font_size_value, it) }.toTypedArray()

    private val previewLabels: Array<String>
        get() = previewValues.map { lineLabel(it) }.toTypedArray()

    /** 行间距选项：0dp ~ 12dp。 */
    private val lineSpacingLabels: Array<String>
        get() = Settings.LINE_SPACINGS.map { getString(R.string.line_spacing_value, it.toInt()) }
            .toTypedArray()

    private fun lineSpacingLabel(value: Float): String =
        getString(R.string.line_spacing_value, value.toInt())

    /** 预览行数选项：3~10 行 + 全显。 */
    private val previewValues: IntArray
        get() = Settings.PREVIEW_LINES + Settings.PREVIEW_ALL

    private fun lineLabel(value: Int): String =
        if (value == Settings.PREVIEW_ALL) getString(R.string.preview_all)
        else getString(R.string.preview_value, value)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        binding.btnBack.setOnClickListener { finish() }
        binding.rowFontSize.setOnClickListener { showFontSizeDialog() }
        binding.rowReadingFontSize.setOnClickListener { showReadingFontSizeDialog() }
        binding.rowFontFamily.setOnClickListener { showFontFamilyDialog() }
        binding.rowPreviewLines.setOnClickListener { showPreviewLinesDialog() }
        binding.rowDisplayStyle.setOnClickListener { showDisplayStyleDialog() }
        binding.rowBackgroundColor.setOnClickListener { showBackgroundColorDialog() }
        binding.rowCardColor.setOnClickListener { showCardColorDialog() }
        binding.rowImageStyle.setOnClickListener { showImageStyleDialog() }
        binding.rowTransitionStyle.setOnClickListener { showTransitionStyleDialog() }
        binding.rowLineSpacing.setOnClickListener { showLineSpacingDialog() }
        binding.rowReadingLineSpacing.setOnClickListener { showReadingLineSpacingDialog() }
        binding.switchSound.isChecked = settings.soundEnabled
        binding.rowSound.setOnClickListener {
            settings.soundEnabled = !settings.soundEnabled
            binding.switchSound.isChecked = settings.soundEnabled
        }
        binding.rowPreviewTags.setOnClickListener { showTagStyleDialog() }
        binding.switchDoubleTap.isChecked = settings.doubleTapEdit
        binding.rowDoubleTap.setOnClickListener {
            settings.doubleTapEdit = !settings.doubleTapEdit
            binding.switchDoubleTap.isChecked = settings.doubleTapEdit
        }
        binding.rowExport.setOnClickListener {
            val stamp = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US)
                .format(java.util.Date())
            exportLauncher.launch("wu_diary_backup_$stamp.zip")
        }
        binding.rowImport.setOnClickListener {
            importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
        }
        binding.rowStorage.setOnClickListener { showStorageDialog() }
        updateFontValue()
        updateReadingFontValue()
        updateFontFamilyValue()
        updatePreviewValue()
        updateDisplayStyleValue()
        updateBackgroundColorValue()
        updateCardColorValue()
        updateTagStyleValue()
        updateImageStyleValue()
        updateTransitionStyleValue()
        updateLineSpacingValue()
        updateReadingLineSpacingValue()
        updateStorageValue()
    }

    private fun showStorageDialog() {
        val options = if (settings.storageTreeUri == null) {
            arrayOf(getString(R.string.storage_pick))
        } else {
            arrayOf(getString(R.string.storage_pick), getString(R.string.storage_reset))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.storage_location)
            .setItems(options) { dialog, which ->
                dialog.dismiss()
                if (which == 0) storageLauncher.launch(null) else resetStorage()
            }
            .show()
    }

    /** 选择目录后：目录里已有笔记就直接接管，否则把当前笔记迁移过去。 */
    private fun changeStorage(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        val current = DiaryStore(this).loadAll()
        Toast.makeText(this, R.string.storage_migrating, Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching {
                val target = ExternalDiaryStore(this, uri)
                if (!target.hasNotes() && current.isNotEmpty()) target.replaceAll(current)
                settings.storageTreeUri = uri.toString()
            }
            runOnUiThread {
                if (result.isSuccess) {
                    updateStorageValue()
                    Toast.makeText(this, R.string.storage_updated, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.storage_error, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** 恢复 App 内部存储，并把当前笔记写回内部。 */
    private fun resetStorage() {
        val current = DiaryStore(this).loadAll()
        settings.storageTreeUri = null
        DiaryStore(this).replaceAll(current)
        updateStorageValue()
        Toast.makeText(this, R.string.storage_reset_done, Toast.LENGTH_SHORT).show()
    }

    private fun updateStorageValue() {
        val raw = settings.storageTreeUri
        binding.textStorageValue.text = if (raw == null) {
            getString(R.string.storage_internal)
        } else {
            val path = Uri.decode(raw).substringAfter(':').ifEmpty { raw }
            getString(R.string.storage_current, path)
        }
    }

    private fun exportNotes(uri: Uri) {
        Toast.makeText(this, R.string.exporting, Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching { BackupManager.export(this, uri) }
            runOnUiThread {
                val count = result.getOrNull()
                Toast.makeText(
                    this,
                    if (count != null) getString(R.string.export_done, count)
                    else getString(R.string.export_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }.start()
    }

    private fun importNotes(uri: Uri) {
        Toast.makeText(this, R.string.importing, Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching { BackupManager.import(this, uri) }
            runOnUiThread {
                val count = result.getOrNull()
                Toast.makeText(
                    this,
                    if (count != null) getString(R.string.import_done, count)
                    else getString(R.string.import_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }.start()
    }

    private fun updateFontValue() {
        binding.textFontValue.text = getString(R.string.font_size_value, settings.fontSp)
    }

    private fun updateReadingFontValue() {
        binding.textReadingFontValue.text = getString(R.string.font_size_value, settings.readingFontSp)
    }

    private fun fontFamilyLabel(family: String): String = when (family) {
        "serif" -> getString(R.string.font_family_serif)
        "monospace" -> getString(R.string.font_family_monospace)
        "casual" -> getString(R.string.font_family_casual)
        else -> getString(R.string.font_family_sans)
    }

    private fun updateFontFamilyValue() {
        binding.textFontFamilyValue.text = fontFamilyLabel(settings.fontFamily)
    }

    /** 字体：列表单选，选中即生效。 */
    private fun showFontFamilyDialog() {
        val families = Settings.FONT_FAMILIES
        val labels = families.map { fontFamilyLabel(it) }.toTypedArray()
        val checked = families.indexOf(settings.fontFamily).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.font_family)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                settings.fontFamily = families[which]
                updateFontFamilyValue()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updatePreviewValue() {
        binding.textPreviewValue.text = lineLabel(settings.previewLines)
    }

    private fun updateDisplayStyleValue() {
        val labelRes = when (settings.displayStyle) {
            Settings.STYLE_MEMOS -> R.string.display_style_memos
            Settings.STYLE_CARD -> R.string.display_style_card
            Settings.STYLE_TIMELINE -> R.string.display_style_timeline
            else -> R.string.display_style_minimal
        }
        binding.textDisplayStyleValue.text = getString(labelRes)
    }

    /** 显示风格：极简/memos/卡片式/时间线，选中即生效。 */
    private fun showDisplayStyleDialog() {
        val styles = intArrayOf(
            Settings.STYLE_MINIMAL,
            Settings.STYLE_MEMOS,
            Settings.STYLE_CARD,
            Settings.STYLE_TIMELINE
        )
        val labels = arrayOf(
            getString(R.string.display_style_minimal),
            getString(R.string.display_style_memos),
            getString(R.string.display_style_card),
            getString(R.string.display_style_timeline)
        )
        val checked = styles.indexOf(settings.displayStyle).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.display_style)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                settings.displayStyle = styles[which]
                updateDisplayStyleValue()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateBackgroundColorValue() {
        val labelRes = if (settings.backgroundTone == Settings.BACKGROUND_WARM) {
            R.string.background_color_warm
        } else {
            R.string.background_color_white
        }
        binding.textBackgroundColorValue.text = getString(labelRes)
    }

    /** 背景颜色：纯白 / 暗黄，选中即生效。 */
    private fun showBackgroundColorDialog() {
        val tones = intArrayOf(Settings.BACKGROUND_WHITE, Settings.BACKGROUND_WARM)
        val labels = arrayOf(
            getString(R.string.background_color_white),
            getString(R.string.background_color_warm)
        )
        val checked = tones.indexOf(settings.backgroundTone).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.background_color)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                settings.backgroundTone = tones[which]
                binding.root.setBackgroundColor(settings.backgroundColor)
                updateBackgroundColorValue()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateCardColorValue() {
        val labelRes = when (settings.cardTone) {
            Settings.CARD_WHITE -> R.string.card_color_white
            Settings.CARD_BEIGE -> R.string.card_color_beige
            else -> R.string.card_color_gray
        }
        binding.textCardColorValue.text = getString(labelRes)
    }

    /** 消息卡片颜色：浅灰 / 白色 / 米黄，memos 与卡片式风格生效。 */
    private fun showCardColorDialog() {
        val tones = intArrayOf(Settings.CARD_GRAY, Settings.CARD_WHITE, Settings.CARD_BEIGE)
        val labels = arrayOf(
            getString(R.string.card_color_gray),
            getString(R.string.card_color_white),
            getString(R.string.card_color_beige)
        )
        val checked = tones.indexOf(settings.cardTone).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.card_color)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                settings.cardTone = tones[which]
                updateCardColorValue()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateImageStyleValue() {
        val labelRes = when (settings.imageStyle) {
            Settings.IMAGE_LARGE -> R.string.image_style_large
            Settings.IMAGE_MEDIUM -> R.string.image_style_medium
            else -> R.string.image_style_small
        }
        binding.textImageStyleValue.text = getString(labelRes)
    }

    /** 图片显示方式：小图 / 中图 / 大图，选中即生效。 */
    private fun showImageStyleDialog() {
        val styles = intArrayOf(Settings.IMAGE_SMALL, Settings.IMAGE_MEDIUM, Settings.IMAGE_LARGE)
        val labels = arrayOf(
            getString(R.string.image_style_small),
            getString(R.string.image_style_medium),
            getString(R.string.image_style_large)
        )
        val checked = styles.indexOf(settings.imageStyle).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.image_style)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                settings.imageStyle = styles[which]
                updateImageStyleValue()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateTransitionStyleValue() {
        val labelRes = if (settings.transitionStyle == Settings.TRANSITION_EXPAND) {
            R.string.transition_style_expand
        } else {
            R.string.transition_style_slide
        }
        binding.textTransitionStyleValue.text = getString(labelRes)
    }

    /** 切换效果：滑出（右侧滑入滑出）/ 展开（原地缩放淡入淡出），选中即生效。 */
    private fun showTransitionStyleDialog() {
        val styles = intArrayOf(Settings.TRANSITION_SLIDE, Settings.TRANSITION_EXPAND)
        val labels = arrayOf(
            getString(R.string.transition_style_slide),
            getString(R.string.transition_style_expand)
        )
        val checked = styles.indexOf(settings.transitionStyle).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.transition_style)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                settings.transitionStyle = styles[which]
                updateTransitionStyleValue()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateTagStyleValue() {
        val labelRes = when (settings.tagStyle) {
            Settings.TAG_BOTTOM -> R.string.tag_style_bottom
            Settings.TAG_HIDDEN -> R.string.tag_style_hidden
            else -> R.string.tag_style_top
        }
        binding.textTagStyleValue.text = getString(labelRes)
    }

    /** 主界面标签位置：笔记顶部 / 笔记底部 / 不显示。 */
    private fun showTagStyleDialog() {
        val styles = intArrayOf(Settings.TAG_TOP, Settings.TAG_BOTTOM, Settings.TAG_HIDDEN)
        val labels = arrayOf(
            getString(R.string.tag_style_top),
            getString(R.string.tag_style_bottom),
            getString(R.string.tag_style_hidden)
        )
        val checked = styles.indexOf(settings.tagStyle).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.preview_tags)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                settings.tagStyle = styles[which]
                updateTagStyleValue()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateLineSpacingValue() {
        binding.textLineSpacingValue.text = lineSpacingLabel(settings.lineSpacing)
    }

    private fun updateReadingLineSpacingValue() {
        binding.textReadingLineSpacingValue.text = lineSpacingLabel(settings.readingLineSpacing)
    }

    private fun showFontSizeDialog() {
        val options = Settings.FONT_SPS
        val current = options.indexOf(settings.fontSp).coerceAtLeast(0)
        showSliderDialog(R.string.font_size, fontLabels.toList(), current) { which ->
            settings.fontSp = options[which]
            updateFontValue()
        }
    }

    private fun showReadingFontSizeDialog() {
        val options = Settings.FONT_SPS
        val current = options.indexOf(settings.readingFontSp).coerceAtLeast(0)
        showSliderDialog(R.string.reading_font_size, fontLabels.toList(), current) { which ->
            settings.readingFontSp = options[which]
            updateReadingFontValue()
        }
    }

    private fun showPreviewLinesDialog() {
        val options = previewValues
        val index = options.indexOf(settings.previewLines)
        val current = if (index >= 0) index else Settings.DEFAULT_PREVIEW_INDEX
        showSliderDialog(R.string.preview_lines, previewLabels.toList(), current) { which ->
            settings.previewLines = options[which]
            updatePreviewValue()
        }
    }

    /** 预览行间距：拖动滑块选择档位，松手即生效。 */
    private fun showLineSpacingDialog() {
        val values = Settings.LINE_SPACINGS
        val index = values.indexOfFirst { it == settings.lineSpacing }
        val current = if (index >= 0) index else Settings.DEFAULT_LINE_SPACING_INDEX
        showSliderDialog(R.string.line_spacing, lineSpacingLabels.toList(), current) { which ->
            settings.lineSpacing = values[which]
            updateLineSpacingValue()
        }
    }

    /** 查看 / 编辑行间距，与预览行间距分开设置。 */
    private fun showReadingLineSpacingDialog() {
        val values = Settings.LINE_SPACINGS
        val index = values.indexOfFirst { it == settings.readingLineSpacing }
        val current = if (index >= 0) index else Settings.DEFAULT_LINE_SPACING_INDEX
        showSliderDialog(
            R.string.reading_line_spacing,
            lineSpacingLabels.toList(),
            current
        ) { which ->
            settings.readingLineSpacing = values[which]
            updateReadingLineSpacingValue()
        }
    }

    /** 通用滑块选择弹窗：拖动到对应档位即显示并生效。 */
    private fun showSliderDialog(
        titleRes: Int,
        labels: List<String>,
        current: Int,
        onChanged: (Int) -> Unit
    ) {
        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (8 * density).toInt(), (24 * density).toInt(), 0)
        }
        val valueText = TextView(this).apply {
            text = labels[current]
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        val seekBar = SeekBar(this).apply {
            max = labels.size - 1
            progress = current
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                valueText.text = labels[progress]
                onChanged(progress)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}

            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        container.addView(valueText)
        container.addView(seekBar)

        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
