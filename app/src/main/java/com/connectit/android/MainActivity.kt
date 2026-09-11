package com.connectit.android

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
