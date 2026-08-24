package com.tuukinmemo

import android.app.Application
import com.tuukinmemo.data.TrackRepository
import org.osmdroid.config.Configuration

/**
 * アプリ全体の初期化
 *
 * osmdroid（OpenStreetMapの地図）は、起動時にユーザーエージェントを
 * 設定しておかないとタイルの取得を拒否される。OSMのタイルサーバは
 * 「どのアプリからのアクセスか分かること」を利用条件にしているため。
 */
class CommuteApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = cacheDir
            osmdroidTileCache = cacheDir.resolve("osmdroid-tiles")
        }
        // 記録途中の軌跡を読み戻しておく（アプリが終了させられていても続きから記録できる）
        TrackRepository.init(this)
    }
}
