package com.wu.diary

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.wu.diary.data.Settings
import com.wu.diary.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: Settings

    private val fontLabels: Array<String>
        get() = arrayOf(
            getString(R.string.font_smaller),
            getString(R.string.font_small),
            getString(R.string.font_default),
            getString(R.string.font_large),
            getString(R.string.font_huge)
        )

    private val previewLabels: Array<String>
        get() = Settings.PREVIEW_LINES.map { getString(R.string.preview_value, it) }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        binding.btnBack.setOnClickListener { finish() }
        binding.rowFontSize.setOnClickListener { showFontSizeDialog() }
        binding.rowPreviewLines.setOnClickListener { showPreviewLinesDialog() }
        updateFontValue()
        updatePreviewValue()
    }

    private fun updateFontValue() {
        val index = Settings.FONT_SCALES.indexOfFirst { it == settings.fontScale }
        binding.textFontValue.text = fontLabels[if (index >= 0) index else Settings.DEFAULT_FONT_INDEX]
    }

    private fun updatePreviewValue() {
        binding.textPreviewValue.text = getString(R.string.preview_value, settings.previewLines)
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
        val options = Settings.PREVIEW_LINES
        val current = options.indexOfFirst { it == settings.previewLines }
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
