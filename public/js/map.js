/**
 * 地図表示（Leaflet + OpenStreetMapタイル）
 *
 * 課金の発生する地図APIは使わない。OSMのタイルは無料だが
 * 「著作権表示を消さないこと」「大量アクセスをしないこと」が利用条件なので、
 * attribution は必ず付けたまま使うこと。
 */

const OSM_URL = 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png';
const OSM_ATTRIBUTION = '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors';

/** 記録がまだ無いときに表示する初期位置（東京駅）。個人の住所は埋め込まない。 */
const DEFAULT_CENTER = [35.681236, 139.767125];

export function createMap(element) {
  const map = L.map(element, { zoomControl: true, attributionControl: true }).setView(DEFAULT_CENTER, 13);
  L.tileLayer(OSM_URL, { maxZoom: 19, attribution: OSM_ATTRIBUTION }).addTo(map);
  return { map, layer: L.layerGroup().addTo(map) };
}

/**
 * 軌跡を描き直す。
 * @param {{map: L.Map, layer: L.LayerGroup}} holder
 * @param {{lat:number,lng:number}[]} points
 */
export function drawTrack(holder, points) {
  const { map, layer } = holder;
  // 次に表示されたときに描き直せるよう、最後に描いた軌跡を覚えておく
  holder.lastPoints = points ?? [];
  layer.clearLayers();
  if (!points || points.length === 0) {
    map.setView(DEFAULT_CENTER, 13);
    return;
  }

  const latlngs = points.map((p) => [p.lat, p.lng]);
  L.polyline(latlngs, { color: '#0f766e', weight: 5, opacity: 0.85 }).addTo(layer);
  L.circleMarker(latlngs[0], { radius: 7, color: '#15803d', fillColor: '#15803d', fillOpacity: 1 })
    .bindTooltip('出発')
    .addTo(layer);
  if (latlngs.length > 1) {
    L.circleMarker(latlngs.at(-1), { radius: 7, color: '#b91c1c', fillColor: '#b91c1c', fillOpacity: 1 })
      .bindTooltip('到着')
      .addTo(layer);
  }

  map.fitBounds(L.latLngBounds(latlngs).pad(0.2), { maxZoom: 17 });
}

/**
 * 非表示のうちに作った地図は自分の大きさを0と思い込んでいる。
 * タブを表示した直後にこれを呼んでサイズを測り直し、表示範囲も取り直す。
 * （Leafletの定番の落とし穴。呼ばないと軌跡が点にしか見えない）
 */
export function refreshMap(holder) {
  if (!holder) return;
  holder.map.invalidateSize();
  const points = holder.lastPoints;
  if (points?.length > 0) {
    holder.map.fitBounds(L.latLngBounds(points.map((p) => [p.lat, p.lng])).pad(0.2), {
      maxZoom: 17,
    });
  }
}
