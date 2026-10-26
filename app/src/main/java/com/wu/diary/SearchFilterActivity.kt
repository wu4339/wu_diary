package com.wu.diary

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.wu.diary.databinding.ActivitySearchFilterBinding

/** 高级搜索筛选页：包含图片、包含定位、有标签、无标签。改动即时回传给搜索页。 */
class SearchFilterActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchFilterBinding

    private var hasImage = false
    private var hasLocation = false
    private var hasTag = false
    private var noTag = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchFilterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hasImage = intent.getBooleanExtra(EXTRA_HAS_IMAGE, false)
        hasLocation = intent.getBooleanExtra(EXTRA_HAS_LOCATION, false)
        hasTag = intent.getBooleanExtra(EXTRA_HAS_TAG, false)
        noTag = intent.getBooleanExtra(EXTRA_NO_TAG, false)

        binding.btnBack.setOnClickListener { finish() }
        binding.rowImage.setOnClickListener { hasImage = !hasImage; render() }
        binding.rowLocation.setOnClickListener { hasLocation = !hasLocation; render() }
        binding.rowTag.setOnClickListener { hasTag = !hasTag; render() }
        binding.rowNoTag.setOnClickListener { noTag = !noTag; render() }
        render()
    }

    /** 每次切换都把当前筛选状态回传，返回搜索页即可生效。 */
    private fun render() {
        binding.checkImage.isVisible = hasImage
        binding.checkLocation.isVisible = hasLocation
        binding.checkTag.isVisible = hasTag
        binding.checkNoTag.isVisible = noTag
        setResult(
            Activity.RESULT_OK,
            Intent()
                .putExtra(EXTRA_HAS_IMAGE, hasImage)
                .putExtra(EXTRA_HAS_LOCATION, hasLocation)
                .putExtra(EXTRA_HAS_TAG, hasTag)
                .putExtra(EXTRA_NO_TAG, noTag)
        )
    }

    companion object {
        const val EXTRA_HAS_IMAGE = "has_image"
        const val EXTRA_HAS_LOCATION = "has_location"
        const val EXTRA_HAS_TAG = "has_tag"
        const val EXTRA_NO_TAG = "no_tag"

        fun intent(
            context: Context,
            hasImage: Boolean,
            hasLocation: Boolean,
            hasTag: Boolean,
            noTag: Boolean
        ): Intent = Intent(context, SearchFilterActivity::class.java)
            .putExtra(EXTRA_HAS_IMAGE, hasImage)
            .putExtra(EXTRA_HAS_LOCATION, hasLocation)
            .putExtra(EXTRA_HAS_TAG, hasTag)
            .putExtra(EXTRA_NO_TAG, noTag)
    }
}
