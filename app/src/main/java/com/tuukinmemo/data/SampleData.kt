package com.tuukinmemo.data

import com.tuukinmemo.model.Crowding
import com.tuukinmemo.model.Direction
import com.tuukinmemo.model.RecordDraft
import com.tuukinmemo.model.TrackPoint
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.random.Random

/**
 * 動作確認用のサンプル記録（デバッグビルドでのみ使う）
 *
 * 集計画面や判定の色分けは、記録が何件かないと確かめようがない。
 * 実際に20回通う前に画面を確認できるようにするためのもの。
 *
 * 座標は東京駅周辺のダミーで、実在の自宅・職場とは関係ない。
 */
object SampleData {

    fun create(store: RecordStore, days: Int = 10) {
        weekdays(days).forEach { date ->
            // 行き: 出発7:40前後、所要45〜75分（判定が3色とも出るようにばらけさせる）
            val departure = LocalTime.of(7, 40).plusMinutes(jitter(15))
            val delayed = Random.nextInt(4) == 0
            val duration = 45L + Random.nextInt(31) + if (delayed) 12 else 0
            store.create(
                RecordDraft(
                    direction = Direction.OUTBOUND,
                    date = date,
                    departureTime = departure,
                    trainTime = departure.plusMinutes(10 + jitter(3)),
                    crowding = Crowding.entries.random(),
                    delayed = delayed,
                    arrivalTime = departure.plusMinutes(duration),
                    note = "サンプルデータ",
                ),
                fakeTrack(20),
            )

            // 帰り: 17:30前後
            val back = LocalTime.of(17, 30).plusMinutes(jitter(20))
            val detour = Random.nextInt(10) < 3
            store.create(
                RecordDraft(
                    direction = Direction.INBOUND,
                    date = date,
                    departureTime = back,
                    trainTime = back.plusMinutes(8),
                    crowding = Crowding.entries.random(),
                    delayed = Random.nextInt(5) == 0,
                    arrivalTime = back.plusMinutes(50L + Random.nextInt(25)),
                    detour = detour,
                    detourNote = if (detour) "本屋に寄った" else "",
                    note = "サンプルデータ",
                ),
                fakeTrack(15),
            )
        }
    }

    private fun jitter(width: Int): Long = (Random.nextInt(width * 2 + 1) - width).toLong()

    /** 平日だけを、今日からさかのぼって集める */
    private fun weekdays(count: Int): List<LocalDate> {
        val dates = mutableListOf<LocalDate>()
        var cursor = LocalDate.now()
        while (dates.size < count) {
            cursor = cursor.minusDays(1)
            if (cursor.dayOfWeek != DayOfWeek.SATURDAY && cursor.dayOfWeek != DayOfWeek.SUNDAY) {
                dates.add(cursor)
            }
        }
        return dates.reversed()
    }

    /** 東京駅付近をまっすぐ北東に進むダミーの軌跡 */
    private fun fakeTrack(points: Int): List<TrackPoint> {
        val startLat = 35.6812 + Random.nextDouble(0.002)
        val startLng = 139.7671 + Random.nextDouble(0.002)
        val now = System.currentTimeMillis()
        return (0 until points).map { index ->
            TrackPoint(
                lat = startLat + index * 0.0004,
                lng = startLng + index * 0.0003,
                timeMillis = now + index * 30_000L,
                accuracyMeters = 8f + Random.nextInt(10),
            )
        }
    }
}
