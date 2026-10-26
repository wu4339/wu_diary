package com.wu.diary

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.wu.diary.data.Settings
import com.wu.diary.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        binding.btnBack.setOnClickListener { finish() }
        binding.rowFontSize.setOnClickListener { showFontSizeDialog() }
        updateFontValue()
    }

    private fun updateFontValue() {
        val labels = arrayOf(
            getString(R.string.font_small),
            getString(R.string.font_default),
            getString(R.string.font_large),
            getString(R.string.font_huge)
        )
        val index = Settings.FONT_SCALES.indexOfFirst { it == settings.fontScale }
        binding.textFontValue.text = labels[if (index >= 0) index else 1]
    }

    private fun showFontSizeDialog() {
        val labels = arrayOf(
            getString(R.string.font_small),
            getString(R.string.font_default),
            getString(R.string.font_large),
            getString(R.string.font_huge)
        )
        val scales = Settings.FONT_SCALES
        val current = scales.indexOfFirst { it == settings.fontScale }.coerceAtLeast(1)
        AlertDialog.Builder(this)
            .setTitle(R.string.font_size)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                settings.fontScale = scales[which]
                updateFontValue()
            }
            .show()
    }
}
