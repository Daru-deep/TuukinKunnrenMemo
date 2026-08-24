package com.tuukinmemo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tuukinmemo.data.DraftStore
import com.tuukinmemo.data.RecordStore
import com.tuukinmemo.data.SampleData
import com.tuukinmemo.data.TrackRepository
import com.tuukinmemo.export.XlsxWriter
import com.tuukinmemo.location.TrackService
import com.tuukinmemo.model.CommuteRecord
import com.tuukinmemo.model.Direction
import com.tuukinmemo.model.RecordDraft
import com.tuukinmemo.model.Summary
import com.tuukinmemo.model.TrackPoint
import com.tuukinmemo.model.summarize
import com.tuukinmemo.model.toDraft
import com.tuukinmemo.model.validateDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 画面が使う状態をまとめて持つ
 *
 * 役割分担:
 *   RecordStore     … 保存（ファイル）
 *   TrackRepository … 記録中のGPS軌跡（Serviceと共有）
 *   ここ            … 両者をつなぎ、画面が読むだけの形（StateFlow）に整える
 *
 * ファイルの読み書きは Dispatchers.IO に逃がし、画面が固まらないようにしている。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val store = RecordStore(File(application.filesDir, "records").apply { mkdirs() })
    private val draftStore = DraftStore(application)

    private val _records = MutableStateFlow<List<CommuteRecord>>(emptyList())
    val records: StateFlow<List<CommuteRecord>> = _records.asStateFlow()

    /** 集計は記録から自動的に導く（別に持たない＝ズレようがない） */
    val summary: StateFlow<Summary> = records
        .map { summarize(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), summarize(emptyList()))

    private val _drafts = MutableStateFlow(
        Direction.entries.associateWith { draftStore.load(it) },
    )
    val drafts: StateFlow<Map<Direction, RecordDraft>> = _drafts.asStateFlow()

    private val _errors = MutableStateFlow<Map<Direction, List<String>>>(emptyMap())
    val errors: StateFlow<Map<Direction, List<String>>> = _errors.asStateFlow()

    /** 画面下に一度だけ出すメッセージ（保存しました、など） */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    val tracks = TrackRepository.tracks
    val recording = TrackRepository.recording
    val gpsMessage = TrackRepository.message

    init {
        TrackRepository.init(application)
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            _records.value = withContext(Dispatchers.IO) { store.load() }
        }
    }

    // -- 入力 ----------------------------------------------------------------

    /** 下書きを1か所だけ書き換える。変更のたびに端末へ保存する */
    fun updateDraft(direction: Direction, transform: (RecordDraft) -> RecordDraft) {
        val updated = transform(draftOf(direction))
        _drafts.value = _drafts.value + (direction to updated)
        draftStore.save(updated)
    }

    fun draftOf(direction: Direction): RecordDraft =
        _drafts.value[direction] ?: RecordDraft(direction)

    /** 保存する。検証に落ちたら理由を画面に返し、保存はしない */
    fun save(direction: Direction) {
        val draft = draftOf(direction)
        val problems = validateDraft(draft)
        if (problems.isNotEmpty()) {
            _errors.value = _errors.value + (direction to problems)
            return
        }
        _errors.value = _errors.value - direction

        viewModelScope.launch {
            val track = TrackRepository.pointsOf(direction)
            withContext(Dispatchers.IO) {
                val editingId = draft.editingId
                if (editingId == null) {
                    store.create(draft, track)
                } else {
                    store.update(editingId, draft, track)
                }
            }
            // 記録中なら止めてから片付ける（保存した軌跡を上書きし続けないため）
            if (recording.value == direction) stopRecording()
            resetDraft(direction)
            reload()
            _messages.emit(if (draft.editingId == null) "記録を保存しました" else "記録を更新しました")
        }
    }

    /** 入力も軌跡も空に戻す */
    fun resetDraft(direction: Direction) {
        val cleared = RecordDraft(direction)
        _drafts.value = _drafts.value + (direction to cleared)
        _errors.value = _errors.value - direction
        draftStore.clear(direction)
        TrackRepository.clear(direction)
    }

    /** 既存の記録を入力画面へ読み込む（編集） */
    fun edit(record: CommuteRecord) {
        viewModelScope.launch {
            val track = withContext(Dispatchers.IO) { store.loadTrack(record.id) }
            val draft = record.toDraft()
            _drafts.value = _drafts.value + (record.direction to draft)
            draftStore.save(draft)
            TrackRepository.replace(record.direction, track)
        }
    }

    fun delete(record: CommuteRecord) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(record.id) }
            // 編集中だった記録を消したら、入力画面も片付ける
            if (draftOf(record.direction).editingId == record.id) resetDraft(record.direction)
            reload()
            _messages.emit("記録を削除しました")
        }
    }

    suspend fun loadTrack(id: String): List<TrackPoint> =
        withContext(Dispatchers.IO) { store.loadTrack(id) }

    // -- GPS -----------------------------------------------------------------

    fun startRecording(direction: Direction) {
        val active = recording.value
        if (active != null && active != direction) {
            viewModelScope.launch { _messages.emit("${active.label}の記録中です。先に停止してください。") }
            return
        }
        TrackService.start(getApplication(), direction)
    }

    fun stopRecording() {
        TrackService.stop(getApplication())
    }

    fun clearTrack(direction: Direction) {
        if (recording.value == direction) stopRecording()
        TrackRepository.clear(direction)
    }

    // -- 出力 ----------------------------------------------------------------

    /** xlsxの中身を作る。保存先の選択は画面側（SAF）に任せる */
    suspend fun buildXlsx(): ByteArray =
        withContext(Dispatchers.Default) { XlsxWriter.build(_records.value) }

    /**
     * サンプル記録を作る（デバッグビルド専用）。
     * 実際に20回通う前に、集計画面や色分けを確認するためのもの。
     */
    fun createSampleData() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { SampleData.create(store, days = 10) }
            reload()
            _messages.emit("サンプル記録を作りました")
        }
    }

    fun notify(text: String) {
        viewModelScope.launch { _messages.emit(text) }
    }
}
