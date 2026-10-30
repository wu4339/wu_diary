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

    /** 找出正文里的第一个合法坐标；没有则返回 null。 */
    fun find(text: String): Pair<Double, Double>? =
        findAll(text).firstOrNull()?.let { it.lat to it.lng }

    /** 用系统地图打开该坐标；没有可用的地图应用时给出提示。 */
    fun openMap(context: Context, lat: Double, lng: Double) {
        val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng")
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure {
                Toast.makeText(context, R.string.map_unavailable, Toast.LENGTH_SHORT).show()
            }
    }
}
