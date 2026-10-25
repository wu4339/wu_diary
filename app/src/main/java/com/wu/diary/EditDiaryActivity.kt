package com.wu.diary

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.wu.diary.data.DiaryStore
import com.wu.diary.databinding.ActivityEditDiaryBinding
import com.wu.diary.model.DiaryEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EditDiaryActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_ID = "entry_id"

        fun intent(context: Context, entryId: String?): Intent =
            Intent(context, EditDiaryActivity::class.java).putExtra(EXTRA_ID, entryId)
    }

    private lateinit var binding: ActivityEditDiaryBinding
    private lateinit var store: DiaryStore
    private var existing: DiaryEntry? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditDiaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = DiaryStore(this)
        intent.getStringExtra(EXTRA_ID)?.let { id ->
            existing = store.load().find { it.id == id }
            existing?.let {
                binding.editTitle.setText(it.title)
                binding.editContent.setText(it.content)
            }
        }

        binding.textEditorTitle.setText(if (existing == null) R.string.add_diary else R.string.edit_diary)
        binding.textEditorDate.text = SimpleDateFormat("M月d日 EEEE", Locale.CHINA)
            .format(Date(existing?.createdAt ?: System.currentTimeMillis()))

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener { save() }
    }

    private fun save() {
        val title = binding.editTitle.text.toString().trim()
        val content = binding.editContent.text.toString().trim()
        if (title.isEmpty() && content.isEmpty()) {
            finish()
            return
        }
        val now = System.currentTimeMillis()
        val entry = existing?.copy(title = title, content = content, updatedAt = now)
            ?: DiaryEntry(title = title, content = content)
        store.save(entry)
        finish()
    }
}
