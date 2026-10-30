package com.wu.diary.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.wu.diary.R

/** 笔记正文里用定位插入的坐标（如「31.230416, 121.473701」）。 */
object GeoPoint {

    /** 纬度在前、经度在后，逗号可中英文。 */
    private val pattern = Regex("(-?\\d{1,3}\\.\\d+)\\s*[,，]\\s*(-?\\d{1,3}\\.\\d+)")

    /** 正文里的一段坐标文本。 */
    data class GeoMatch(val start: Int, val end: Int, val lat: Double, val lng: Double)

    /** 找出正文里所有合法坐标，记录各自在文本中的位置。 */
    fun findAll(text: String): List<GeoMatch> {
        val result = mutableListOf<GeoMatch>()
        pattern.findAll(text).forEach { match ->
            val lat = match.groupValues[1].toDoubleOrNull() ?: return@forEach
            val lng = match.groupValues[2].toDoubleOrNull() ?: return@forEach
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return@forEach
            result.add(GeoMatch(match.range.first, match.range.last + 1, lat, lng))
        }
        return result
    }

    /** 取坐标前面「位置坐标(XX, 」里的地点名；没有地点名或只有数字时返回 null。 */
    fun labelBefore(text: String, match: GeoMatch): String? {
        val prefix = text.substring(0, match.start)
        val open = prefix.lastIndexOf(LOCATION_PREFIX)
        if (open < 0) return null
        val label = prefix.substring(open + LOCATION_PREFIX.length)
            .trim()
            .trimEnd(',', '，')
            .trim()
        return label.takeIf { it.isNotBlank() && !numericOnly.matches(it) }
    }

    private const val LOCATION_PREFIX = "位置坐标("

    private val numericOnly = Regex("[-\\d.,，\\s]+")

    /** 用系统地图打开该坐标；传入 label 时地图上会带名称标注，没有地图应用时给出提示。 */
    fun openMap(context: Context, lat: Double, lng: Double, label: String? = null) {
        val coords = "$lat,$lng"
        val query = if (label.isNullOrBlank()) coords else "$coords(${Uri.encode(label)})"
        val uri = Uri.parse("geo:$coords?q=$query")
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure {
                Toast.makeText(context, R.string.map_unavailable, Toast.LENGTH_SHORT).show()
            }
    }
}
