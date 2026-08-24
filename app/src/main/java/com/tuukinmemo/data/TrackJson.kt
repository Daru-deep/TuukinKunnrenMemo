package com.tuukinmemo.data

import com.tuukinmemo.model.TrackPoint
import org.json.JSONArray
import org.json.JSONObject

/**
 * GPS軌跡とJSONの相互変換。
 * 保存済みの記録（RecordStore）と記録中の軌跡（TrackRepository）で
 * 同じ形式を使うため、変換をここに1つだけ置く。
 */
internal object TrackJson {

    fun encode(points: List<TrackPoint>): String {
        val array = JSONArray()
        points.forEach { point ->
            array.put(
                JSONObject()
                    // 小数6桁（約10cm）あれば十分。桁を落としてファイルを小さく保つ
                    .put("lat", round6(point.lat))
                    .put("lng", round6(point.lng))
                    .put("t", point.timeMillis)
                    .put("acc", point.accuracyMeters.toDouble()),
            )
        }
        return array.toString()
    }

    fun decode(text: String): List<TrackPoint> = runCatching {
        val array = JSONArray(text)
        (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            val lat = json.optDouble("lat", Double.NaN)
            val lng = json.optDouble("lng", Double.NaN)
            if (lat.isNaN() || lng.isNaN()) return@mapNotNull null
            if (lat < -90 || lat > 90 || lng < -180 || lng > 180) return@mapNotNull null
            TrackPoint(
                lat = lat,
                lng = lng,
                timeMillis = json.optLong("t"),
                accuracyMeters = json.optDouble("acc", 0.0).toFloat(),
            )
        }
    }.getOrDefault(emptyList())

    private fun round6(value: Double): Double = Math.round(value * 1_000_000.0) / 1_000_000.0
}
