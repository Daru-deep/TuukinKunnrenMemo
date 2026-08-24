package com.tuukinmemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.tuukinmemo.ui.CommuteApp
import com.tuukinmemo.ui.theme.CommuteTheme

/**
 * 唯一の画面。
 * 行き／帰り／集計の切り替えは Compose の中（CommuteApp）で行うので、
 * Activity はここ1つで足りる。
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CommuteTheme {
                CommuteApp(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 他の端末や編集から戻ったときに、一覧を最新にしておく
        viewModel.reload()
    }
}
