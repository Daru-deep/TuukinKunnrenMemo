// ルートのビルドファイル。プラグインの版だけを宣言し、実体は :app 側で適用する。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
