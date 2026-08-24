package com.tuukinmemo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.tuukinmemo.model.TrackPoint
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * GPS軌跡の地図表示（osmdroid + OpenStreetMap）
 *
 * Google Maps を使わない理由:
 *   Maps SDK は APIキーの発行に課金アカウントの登録が要る。
 *   osmdroid + OSMのタイルはキーも登録も不要で、この用途なら十分。
 *   ただしOSMのタイルサーバは善意で公開されているものなので、
 *   ユーザーエージェントの設定（CommuteApplication）と
 *   常識的なアクセス量を守ること。
 *
 * Compose にはまだ地図の部品が無いので、昔ながらの View を
 * AndroidView で埋め込んでいる。
 */

/** 記録がまだ無いときの初期位置（東京駅）。個人の住所は埋め込まない。 */
private val DEFAULT_CENTER = GeoPoint(35.681236, 139.767125)

@Composable
fun TrackMap(
    points: List<TrackPoint>,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            MapView(context).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(14.0)
                controller.setCenter(DEFAULT_CENTER)
            }
        },
        update = { map -> map.drawTrack(points) },
        // 画面から外れたら地図の後始末をする（タイル取得を止める）
        onRelease = { it.onDetach() },
    )
}

private fun MapView.drawTrack(points: List<TrackPoint>) {
    overlays.clear()
    if (points.isEmpty()) {
        controller.setCenter(DEFAULT_CENTER)
        invalidate()
        return
    }

    val geoPoints = points.map { GeoPoint(it.lat, it.lng) }

    overlays.add(
        Polyline(this).apply {
            setPoints(geoPoints)
            outlinePaint.strokeWidth = 12f
            outlinePaint.color = 0xFF0F766E.toInt()
        },
    )
    overlays.add(marker(geoPoints.first(), "出発"))
    if (geoPoints.size > 1) overlays.add(marker(geoPoints.last(), "到着"))

    // 地図の大きさが決まる前に範囲指定をすると失敗するので、レイアウト後に実行する
    post {
        zoomToBoundingBox(BoundingBox.fromGeoPoints(geoPoints).increaseByScale(1.3f), false, 48)
    }
    invalidate()
}

private fun MapView.marker(point: GeoPoint, title: String) = Marker(this).apply {
    position = point
    this.title = title
    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
}
