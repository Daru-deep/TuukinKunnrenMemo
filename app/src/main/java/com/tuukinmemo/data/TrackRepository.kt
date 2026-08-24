package com.tuukinmemo.data

import android.content.Context
import com.tuukinmemo.model.Direction
import com.tuukinmemo.model.MAX_ACCURACY_METERS
import com.tuukinmemo.model.TrackPoint
import com.tuukinmemo.model.shouldKeepPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 記録中のGPS軌跡を持つ置き場
 *
 * なぜ独立した object なのか:
 *   点を受け取るのは Service（画面が無くても動く）、
 *   表示するのは Compose の画面、という別々の場所から同じ軌跡を触るため。
 *   ViewModel に持たせると、画面が閉じたときに消えてしまう。
 *
 * 点が増えるたびにファイルへ書いているので、
 * 通勤中にアプリがOSに終了されても軌跡は残る（次に開けば続きから記録できる）。
 */
object TrackRepository {

    private lateinit var directory: File

    private val _tracks = MutableStateFlow<Map<Direction, List<TrackPoint>>>(emptyMap())

    /** 区分ごとの「記録中の軌跡」 */
    val tracks: StateFlow<Map<Direction, List<TrackPoint>>> = _tracks.asStateFlow()

    private val _recording = MutableStateFlow<Direction?>(null)

    /** いま記録中の区分。していなければ null */
    val recording: StateFlow<Direction?> = _recording.asStateFlow()

    private val _message = MutableStateFlow("")

    /** 直近の状態メッセージ（測位の誤差など）。画面にそのまま出す */
    val message: StateFlow<String> = _message.asStateFlow()

    /** アプリ起動時に呼び、前回の記録途中の軌跡を読み戻す */
    fun init(context: Context) {
        if (::directory.isInitialized) return
        directory = File(context.filesDir, "inprogress").apply { mkdirs() }
        _tracks.value = Direction.entries.associateWith { direction ->
            val file = fileOf(direction)
            if (file.exists()) TrackJson.decode(file.readText()) else emptyList()
        }
    }

    fun pointsOf(direction: Direction): List<TrackPoint> = _tracks.value[direction].orEmpty()

    /**
     * 測位を1点受け取る。間引きの条件に合わなければ捨てる。
     * @return 実際に軌跡へ足したかどうか
     */
    @Synchronized
    fun add(direction: Direction, point: TrackPoint): Boolean {
        val current = pointsOf(direction)
        if (!shouldKeepPoint(current.lastOrNull(), point)) {
            if (point.accuracyMeters > MAX_ACCURACY_METERS) {
                _message.value = "測位の精度が粗いため無視しました（±${point.accuracyMeters.toInt()}m）"
            }
            return false
        }
        val updated = current + point
        _tracks.value = _tracks.value + (direction to updated)
        _message.value = "記録中（${updated.size}点 / 誤差±${point.accuracyMeters.toInt()}m）"
        persist(direction, updated)
        return true
    }

    /** 既存の記録を編集するときに、その軌跡を読み込む */
    @Synchronized
    fun replace(direction: Direction, points: List<TrackPoint>) {
        _tracks.value = _tracks.value + (direction to points)
        persist(direction, points)
    }

    @Synchronized
    fun clear(direction: Direction) {
        _tracks.value = _tracks.value + (direction to emptyList())
        fileOf(direction).delete()
    }

    fun markRecording(direction: Direction?) {
        _recording.value = direction
        if (direction == null) _message.value = ""
    }

    fun setMessage(text: String) {
        _message.value = text
    }

    private fun persist(direction: Direction, points: List<TrackPoint>) {
        if (!::directory.isInitialized) return
        runCatching { fileOf(direction).writeText(TrackJson.encode(points)) }
    }

    private fun fileOf(direction: Direction) = File(directory, "track-${direction.id}.json")
}
