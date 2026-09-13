package com.connectit.android

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.connectit.android.repo.AppSettings
import com.connectit.android.repo.AppThemeMode
import com.connectit.android.repo.SettingsRepository
import com.connectit.android.ui.ConnectItApp
import com.connectit.android.ui.MainViewModel
import com.connectit.android.ui.theme.ConnectItTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val legacyStoragePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 35 起系統會強制 edge-to-edge,若不主動呼叫這個,系統列(尤其手勢導覽列)
        // 只會套用預設的淺色底色,在深色主題下會變成一條突兀的白條。
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // API 29 以上改用 MediaStore.Downloads 寫入公用 Download 資料夾,不需要這個權限;
        // 只有更舊的裝置(API 26-28)才需要用傳統檔案路徑,得先跟使用者要權限。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val settingsRepository = SettingsRepository(applicationContext)

        setContent {
            val settings by settingsRepository.settings.collectAsState(
                initial = AppSettings(settingsRepository.defaultDeviceName(), AppThemeMode.AUTO)
            )

            ConnectItTheme(themeMode = settings.themeMode) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val service by viewModel.service.collectAsState()
                    val current = service
                    if (current == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        // 使用者從通知按下「關閉」:服務會自己停止,這裡另外把畫面也收掉並從
                        // 最近使用的 App 清單移除,讓「關閉」的效果跟使用者預期的「整個 App 關掉」一致。
                        LaunchedEffect(current) {
                            current.exitRequested.collect { finishAndRemoveTask() }
                        }
                        ConnectItApp(service = current)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.bind()
    }

    override fun onStop() {
        viewModel.unbind()
        super.onStop()
    }
}
