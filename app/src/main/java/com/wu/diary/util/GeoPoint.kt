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

    /**
     * 用系统地图打开该坐标；没有地图应用时给出提示。
     * 只带坐标、不带名称：部分地图 App（如高德）会把 q 里的中文名称显示成 %E6% 这样的编码。
     */
    fun openMap(context: Context, lat: Double, lng: Double) {
        val coords = "$lat,$lng"
        val uri = Uri.parse("geo:$coords?q=$coords")
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure {
                Toast.makeText(context, R.string.map_unavailable, Toast.LENGTH_SHORT).show()
            }
    }
}
