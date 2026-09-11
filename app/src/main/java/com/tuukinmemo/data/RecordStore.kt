package com.tuukinmemo.data

import com.tuukinmemo.model.CommuteRecord
import com.tuukinmemo.model.Crowding
import com.tuukinmemo.model.Direction
import com.tuukinmemo.model.RecordDraft
import com.tuukinmemo.model.TrackPoint
import com.tuukinmemo.model.formatTime
import com.tuukinmemo.model.parseTime
import com.tuukinmemo.model.recordOrder
import com.tuukinmemo.model.toRecord
import com.tuukinmemo.model.trackDistanceKm
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.UUID

/**
 * 記録の保存（JSONファイル）
 *
 * なぜ Room（SQLite）ではなくJSONか:
 * - 記録は1日2件、年に500件程度。検索も期間集計もメモリ上で十分に速い
 * - アノテーション処理（KSP）を足さないぶんビルドが単純で、壊れにくい
 * - `adb pull` でそのまま読める形なので、バックアップと中身の確認が楽
 * 件数が数千件を超えて重くなったら、このクラスの関数の形を保ったまま
 * 中身をRoomに差し替えればよい（呼び出し側は変えずに済む）。
 *
 * 置き場所は Context.filesDir 配下（アプリ専用領域）を渡す想定。
 * 他のアプリからは読めないので、通勤経路のような個人情報を置いても外に漏れない。
 *
 * ファイル構成:
 *   records.json          … 記録の一覧（軌跡は含まない = 一覧表示が軽い）
 *   tracks/<id>.json      … その記録のGPS軌跡
 */
class RecordStore(private val baseDir: File) {

    private val recordsFile = File(baseDir, "records.json")
    private val tracksDir = File(baseDir, "tracks")

    init {
        tracksDir.mkdirs()
    }

    /** 全記録を日付順で返す。ファイルが壊れていても空リストを返して起動は止めない。 */
    @Synchronized
    fun load(): List<CommuteRecord> = readRecords().sortedWith(recordOrder)

    @Synchronized
    fun find(id: String): CommuteRecord? = readRecords().firstOrNull { it.id == id }

    /** その記録のGPS軌跡。無ければ空リスト。 */
    @Synchronized
    fun loadTrack(id: String): List<TrackPoint> {
        val file = trackFile(id) ?: return emptyList()
        if (!file.exists()) return emptyList()
        return TrackJson.decode(file.readText())
    }

    /**
     * 新規保存。draft は検証済みであること（validateDraft を先に通す）。
     * @param track GPSで記録した軌跡。空なら軌跡ファイルは作らない。
     */
    @Synchronized
    fun create(draft: RecordDraft, track: List<TrackPoint>): CommuteRecord {
        val now = System.currentTimeMillis()
        val record = draft.toRecord(
            id = UUID.randomUUID().toString(),
            trackPoints = track.size,
            trackDistanceKm = trackDistanceKm(track),
            createdAt = now,
            updatedAt = now,
        )
        writeTrack(record.id, track)
        writeRecords(readRecords() + record)
        return record
    }

    /**
     * 既存の記録を更新する。
     * @param track null なら既存の軌跡をそのまま残す（時刻だけ直したいときに軌跡を消さない）
     */
    @Synchronized
    fun update(id: String, draft: RecordDraft, track: List<TrackPoint>?): CommuteRecord {
        val records = readRecords()
        val existing = records.firstOrNull { it.id == id }
            ?: throw NoSuchElementException("記録が見つかりません: $id")

        val updated = if (track == null) {
            draft.toRecord(id, existing.trackPoints, existing.trackDistanceKm, existing.createdAt, System.currentTimeMillis())
        } else {
            writeTrack(id, track)
            draft.toRecord(id, track.size, trackDistanceKm(track), existing.createdAt, System.currentTimeMillis())
        }

        writeRecords(records.map { if (it.id == id) updated else it })
        return updated
    }

    @Synchronized
    fun delete(id: String) {
        trackFile(id)?.delete()
        writeRecords(readRecords().filterNot { it.id == id })
    }

    // -- 内部 ----------------------------------------------------------------

    /** id から軌跡ファイルの場所を決める。UUID以外は受け付けない（パスの細工を防ぐ）。 */
    private fun trackFile(id: String): File? {
        if (!Regex("^[0-9a-fA-F-]{36}$").matches(id)) return null
        return File(tracksDir, "$id.json")
    }

    private fun readRecords(): List<CommuteRecord> {
        if (!recordsFile.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(recordsFile.readText())
            (0 until array.length()).mapNotNull { index ->
                runCatching { parseRecord(array.getJSONObject(index)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    private fun writeRecords(records: List<CommuteRecord>) {
        val array = JSONArray()
        records.sortedWith(recordOrder).forEach { array.put(toJson(it)) }
        writeAtomically(recordsFile, array.toString(2))
    }

    private fun writeTrack(id: String, track: List<TrackPoint>) {
        val file = trackFile(id) ?: return
        if (track.isEmpty()) {
            file.delete()
            return
        }
        writeAtomically(file, TrackJson.encode(track))
    }

    /**
     * 一時ファイルに書いてから置き換える。
     * 書き込みの途中で電源が切れても、壊れたJSONが残らない。
     */
    private fun writeAtomically(file: File, text: String) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            // renameが使えない環境向けの保険
            file.writeText(text)
            temp.delete()
        }
    }

    private fun toJson(record: CommuteRecord): JSONObject = JSONObject()
        .put("id", record.id)
        .put("direction", record.direction.id)
        .put("date", record.date.toString())
        .put("wakeTime", formatTime(record.wakeTime))
        .put("departureTime", formatTime(record.departureTime))
        .put("homeStationTime", formatTime(record.homeStationTime))
        .put("trainTime", formatTime(record.trainTime))
        .put("workStationTime", formatTime(record.workStationTime))
        .put("crowding", record.crowding?.id ?: "")
        .put("delayed", record.delayed)
        .put("arrivalTime", formatTime(record.arrivalTime))
        .put("detour", record.detour)
        .put("detourNote", record.detourNote)
        .put("note", record.note)
        .put("trackPoints", record.trackPoints)
        .put("trackDistanceKm", record.trackDistanceKm)
        .put("createdAt", record.createdAt)
        .put("updatedAt", record.updatedAt)

    private fun parseRecord(json: JSONObject): CommuteRecord = CommuteRecord(
        id = json.getString("id"),
        direction = Direction.fromId(json.optString("direction")) ?: Direction.OUTBOUND,
        date = LocalDate.parse(json.getString("date")),
        // 起床・駅着は後から足した項目。古い記録には無いので null のまま読む
        wakeTime = parseTime(json.optString("wakeTime")),
        departureTime = parseTime(json.getString("departureTime"))
            ?: error("出発時刻が壊れています"),
        homeStationTime = parseTime(json.optString("homeStationTime")),
        trainTime = parseTime(json.optString("trainTime")),
        workStationTime = parseTime(json.optString("workStationTime")),
        crowding = Crowding.fromId(json.optString("crowding")),
        delayed = json.optBoolean("delayed"),
        arrivalTime = parseTime(json.getString("arrivalTime"))
            ?: error("到着時刻が壊れています"),
        detour = json.optBoolean("detour"),
        detourNote = json.optString("detourNote"),
        note = json.optString("note"),
        trackPoints = json.optInt("trackPoints"),
        trackDistanceKm = json.optDouble("trackDistanceKm", 0.0),
        createdAt = json.optLong("createdAt"),
        updatedAt = json.optLong("updatedAt"),
    )

}
