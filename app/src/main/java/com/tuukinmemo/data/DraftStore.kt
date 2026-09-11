package com.tuukinmemo.data

import android.content.Context
import com.tuukinmemo.model.Crowding
import com.tuukinmemo.model.Direction
import com.tuukinmemo.model.RecordDraft
import com.tuukinmemo.model.formatTime
import com.tuukinmemo.model.parseTime
import org.json.JSONObject
import java.time.LocalDate

/**
 * 入力途中の内容を端末に残す
 *
 * 通勤の途中で他のアプリに切り替えたり、OSにアプリを終了されたりしても
 * 入力済みの時刻が消えないようにする。これが無いと、
 * 「出発時刻を入れたのに到着時に消えていた」という一番痛い失敗が起きる。
 *
 * 量が少ないので SharedPreferences で十分（DataStoreを足すほどではない）。
 */
class DraftStore(context: Context) {

    private val preferences = context.getSharedPreferences("drafts", Context.MODE_PRIVATE)

    fun load(direction: Direction): RecordDraft {
        val text = preferences.getString(direction.id, null) ?: return RecordDraft(direction)
        return runCatching {
            val json = JSONObject(text)
            RecordDraft(
                direction = direction,
                editingId = json.optString("editingId").ifEmpty { null },
                date = json.optString("date").takeIf { it.isNotEmpty() }
                    ?.let { LocalDate.parse(it) } ?: LocalDate.now(),
                wakeTime = parseTime(json.optString("wakeTime")),
                departureTime = parseTime(json.optString("departureTime")),
                homeStationTime = parseTime(json.optString("homeStationTime")),
                trainTime = parseTime(json.optString("trainTime")),
                workStationTime = parseTime(json.optString("workStationTime")),
                crowding = Crowding.fromId(json.optString("crowding")),
                delayed = json.optBoolean("delayed"),
                arrivalTime = parseTime(json.optString("arrivalTime")),
                detour = json.optBoolean("detour"),
                detourNote = json.optString("detourNote"),
                note = json.optString("note"),
            )
        }.getOrDefault(RecordDraft(direction))
    }

    fun save(draft: RecordDraft) {
        val json = JSONObject()
            .put("editingId", draft.editingId ?: "")
            .put("date", draft.date.toString())
            .put("wakeTime", formatTime(draft.wakeTime))
            .put("departureTime", formatTime(draft.departureTime))
            .put("homeStationTime", formatTime(draft.homeStationTime))
            .put("trainTime", formatTime(draft.trainTime))
            .put("workStationTime", formatTime(draft.workStationTime))
            .put("crowding", draft.crowding?.id ?: "")
            .put("delayed", draft.delayed)
            .put("arrivalTime", formatTime(draft.arrivalTime))
            .put("detour", draft.detour)
            .put("detourNote", draft.detourNote)
            .put("note", draft.note)
        preferences.edit().putString(draft.direction.id, json.toString()).apply()
    }

    fun clear(direction: Direction) {
        preferences.edit().remove(direction.id).apply()
    }
}
