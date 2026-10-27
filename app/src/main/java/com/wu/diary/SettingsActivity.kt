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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.wu.diary.data.DiaryStore
import com.wu.diary.data.ExternalDiaryStore
import com.wu.diary.data.Settings
import com.wu.diary.databinding.ActivitySettingsBinding
import com.wu.diary.util.BackupManager

class SettingsActivity : AppCompatActivity() {

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

    private val fontLabels: Array<String>
        get() = arrayOf(
            getString(R.string.font_smaller),
            getString(R.string.font_small),
            getString(R.string.font_default),
            getString(R.string.font_large),
            getString(R.string.font_huge)
        )

    private val previewLabels: Array<String>
        get() = previewValues.map { lineLabel(it) }.toTypedArray()

    private val lineSpacingLabels: Array<String>
        get() = arrayOf(
            getString(R.string.line_spacing_none),
            getString(R.string.line_spacing_small),
            getString(R.string.line_spacing_default),
            getString(R.string.line_spacing_large),
            getString(R.string.line_spacing_huge)
        )

    private fun lineSpacingLabel(value: Float): String {
        val index = Settings.LINE_SPACINGS.indexOfFirst { it == value }
        val label = lineSpacingLabels[if (index >= 0) index else Settings.DEFAULT_LINE_SPACING_INDEX]
        return getString(R.string.line_spacing_value, label, value.toInt())
    }

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
        binding.rowPreviewLines.setOnClickListener { showPreviewLinesDialog() }
        binding.rowLineSpacing.setOnClickListener { showLineSpacingDialog() }
        binding.switchSound.isChecked = settings.soundEnabled
        binding.rowSound.setOnClickListener {
            settings.soundEnabled = !settings.soundEnabled
            binding.switchSound.isChecked = settings.soundEnabled
        }
        binding.switchPreviewTags.isChecked = settings.previewTags
        binding.rowPreviewTags.setOnClickListener {
            settings.previewTags = !settings.previewTags
            binding.switchPreviewTags.isChecked = settings.previewTags
        }
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
        updatePreviewValue()
        updateLineSpacingValue()
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
        val current = DiaryStore(this).load()
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
        val current = DiaryStore(this).load()
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
        val index = Settings.FONT_SCALES.indexOfFirst { it == settings.fontScale }
        binding.textFontValue.text = fontLabels[if (index >= 0) index else Settings.DEFAULT_FONT_INDEX]
    }

    private fun updatePreviewValue() {
        binding.textPreviewValue.text = lineLabel(settings.previewLines)
    }

    private fun updateLineSpacingValue() {
        binding.textLineSpacingValue.text = lineSpacingLabel(settings.lineSpacing)
    }

    private fun showFontSizeDialog() {
        val scales = Settings.FONT_SCALES
        val index = scales.indexOfFirst { it == settings.fontScale }
        val current = if (index >= 0) index else Settings.DEFAULT_FONT_INDEX
        showSliderDialog(R.string.font_size, fontLabels.toList(), current) { which ->
            settings.fontScale = scales[which]
            updateFontValue()
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

    /** 行间距：拖动滑块选择档位，松手即生效。 */
    private fun showLineSpacingDialog() {
        val values = Settings.LINE_SPACINGS
        val index = values.indexOfFirst { it == settings.lineSpacing }
        val current = if (index >= 0) index else Settings.DEFAULT_LINE_SPACING_INDEX
        val labels = values.indices.map {
            getString(R.string.line_spacing_value, lineSpacingLabels[it], values[it].toInt())
        }
        showSliderDialog(R.string.line_spacing, labels, current) { which ->
            settings.lineSpacing = values[which]
            updateLineSpacingValue()
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
