package com.wu.diary

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.wu.diary.data.Settings
import com.wu.diary.databinding.ActivitySettingsBinding
import com.wu.diary.util.BackupManager

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: Settings

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
        binding.switchSound.isChecked = settings.soundEnabled
        binding.rowSound.setOnClickListener {
            settings.soundEnabled = !settings.soundEnabled
            binding.switchSound.isChecked = settings.soundEnabled
        }
        binding.switchDoubleTap.isChecked = settings.doubleTapEdit
        binding.rowDoubleTap.setOnClickListener {
            settings.doubleTapEdit = !settings.doubleTapEdit
            binding.switchDoubleTap.isChecked = settings.doubleTapEdit
        }
        binding.rowExport.setOnClickListener {
            exportLauncher.launch("wu_diary_backup.zip")
        }
        binding.rowImport.setOnClickListener {
            importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
        }
        updateFontValue()
        updatePreviewValue()
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

    private fun showFontSizeDialog() {
        val scales = Settings.FONT_SCALES
        val current = scales.indexOfFirst { it == settings.fontScale }
            .coerceAtLeast(Settings.DEFAULT_FONT_INDEX)
        AlertDialog.Builder(this)
            .setTitle(R.string.font_size)
            .setSingleChoiceItems(fontLabels, current) { dialog, which ->
                dialog.dismiss()
                settings.fontScale = scales[which]
                updateFontValue()
            }
            .show()
    }

    private fun showPreviewLinesDialog() {
        val options = previewValues
        val current = options.indexOf(settings.previewLines)
            .coerceAtLeast(Settings.DEFAULT_PREVIEW_INDEX)
        AlertDialog.Builder(this)
            .setTitle(R.string.preview_lines)
            .setSingleChoiceItems(previewLabels, current) { dialog, which ->
                dialog.dismiss()
                settings.previewLines = options[which]
                updatePreviewValue()
            }
            .show()
    }
}
