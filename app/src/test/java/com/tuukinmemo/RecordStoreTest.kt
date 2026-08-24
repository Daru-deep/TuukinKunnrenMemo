package com.tuukinmemo

import com.tuukinmemo.data.RecordStore
import com.tuukinmemo.model.Crowding
import com.tuukinmemo.model.Direction
import com.tuukinmemo.model.RecordDraft
import com.tuukinmemo.model.TrackPoint
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 保存層のテスト。
 * org.json は Android の標準API だが、`testImplementation("org.json:json")` を
 * 入れてあるのでPC上のユニットテストでもそのまま動く。
 */
class RecordStoreTest {

    private lateinit var dir: File
    private lateinit var store: RecordStore

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("tuukin-test").toFile()
        store = RecordStore(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun draft(
        direction: Direction = Direction.OUTBOUND,
        date: String = "2026-08-21",
        departure: String = "07:45",
        arrival: String = "08:25",
    ) = RecordDraft(
        direction = direction,
        date = LocalDate.parse(date),
        departureTime = LocalTime.parse(departure),
        trainTime = LocalTime.parse("07:58"),
        crowding = Crowding.CROWDED,
        delayed = true,
        arrivalTime = LocalTime.parse(arrival),
        detour = true,
        detourNote = "コンビニ",
        note = "初回",
    )

    private val track = listOf(
        TrackPoint(35.7295, 139.7109, 1_000, 12f),
        TrackPoint(35.7305, 139.7109, 6_000, 9f),
    )

    @Test
    fun `保存して読み直すと同じ内容になる`() {
        val saved = store.create(draft(), track)

        val loaded = store.load()
        assertEquals(1, loaded.size)
        val record = loaded.single()
        assertEquals(saved.id, record.id)
        assertEquals(LocalTime.parse("07:58"), record.trainTime)
        assertEquals(Crowding.CROWDED, record.crowding)
        assertTrue(record.delayed)
        assertEquals("コンビニ", record.detourNote)
        assertEquals(2, record.trackPoints)
        assertTrue(record.trackDistanceKm > 0)
        assertEquals(40, record.durationMinutes)
    }

    @Test
    fun `軌跡は別ファイルに置かれ、一覧の読み込みでは触らない`() {
        val saved = store.create(draft(), track)

        assertTrue(File(dir, "tracks/${saved.id}.json").exists())
        assertFalse(File(dir, "records.json").readText().contains("139.71"), "一覧には座標を持たない")
        assertEquals(2, store.loadTrack(saved.id).size)
        assertEquals(35.7295, store.loadTrack(saved.id).first().lat, 0.00001)
    }

    @Test
    fun `更新で軌跡を渡さなければ既存の軌跡は残る`() {
        val saved = store.create(draft(), track)

        val updated = store.update(saved.id, draft(arrival = "09:05").copy(editingId = saved.id), null)
        assertEquals(LocalTime.parse("09:05"), updated.arrivalTime)
        assertEquals(2, updated.trackPoints, "軌跡が消えていない")
        assertEquals(2, store.loadTrack(saved.id).size)
        assertEquals(saved.createdAt, updated.createdAt, "作成日時は変わらない")
    }

    @Test
    fun `更新で軌跡を渡せば置き換わる`() {
        val saved = store.create(draft(), track)
        val updated = store.update(saved.id, draft().copy(editingId = saved.id), emptyList())
        assertEquals(0, updated.trackPoints)
        assertTrue(store.loadTrack(saved.id).isEmpty())
    }

    @Test
    fun `削除すると記録も軌跡も消える`() {
        val saved = store.create(draft(), track)
        store.delete(saved.id)

        assertTrue(store.load().isEmpty())
        assertNull(store.find(saved.id))
        assertTrue(store.loadTrack(saved.id).isEmpty())
        assertFalse(File(dir, "tracks/${saved.id}.json").exists())
    }

    @Test
    fun `一覧は日付順に並ぶ`() {
        listOf("2026-09-01", "2026-08-15", "2026-08-31").forEach {
            store.create(draft(date = it), emptyList())
        }
        assertEquals(
            listOf("2026-08-15", "2026-08-31", "2026-09-01"),
            store.load().map { it.date.toString() },
        )
    }

    @Test
    fun `ファイルが壊れていても起動を止めない`() {
        store.create(draft(), track)
        File(dir, "records.json").writeText("{壊れたJSON")

        assertTrue(store.load().isEmpty(), "空として扱う")
        // 壊れたあとでも新しい記録は保存できる
        store.create(draft(date = "2026-08-22"), emptyList())
        assertEquals(1, store.load().size)
    }

    @Test
    fun `idの形をしていない軌跡は読み書きしない（パスの細工を防ぐ）`() {
        assertTrue(store.loadTrack("../../etc/passwd").isEmpty())
        store.delete("../../etc/passwd") // 例外にならず、何も壊さない
    }

    private fun assertFalse(value: Boolean, message: String = "") = assertTrue(!value, message)
}
