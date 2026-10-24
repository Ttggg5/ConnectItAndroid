package com.connectit.android

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import android.view.KeyEvent
import androidx.activity.viewModels
import com.connectit.android.net.RemoteKeys
import com.connectit.android.quicksend.ShareShortcuts
import com.connectit.android.screen.RemoteControlPhase
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
import com.connectit.android.ui.tv.ConnectItTvTheme
import com.connectit.android.ui.tv.TvAppRoot
import com.connectit.android.util.isTelevision

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val legacyStoragePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** 快速設定磚(ACTION_QUICK_SEND):直接開檔案選擇器,選好之後走跟「分享」進來一樣的選裝置流程。 */
    private val quickSendPicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) viewModel.setPendingShare(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 35 起系統會強制 edge-to-edge,若不主動呼叫這個,系統列(尤其手勢導覽列)
        // 只會套用預設的淺色底色,在深色主題下會變成一條突兀的白條。
        enableEdgeToEdge()

        val isTv = isTelevision(this)

        // 手機/平板版改在首次啟動引導的最後一步(或引導完成後的下次開啟)才詢問通知權限,
        // 見 ConnectItApp;電視沒有引導流程,維持一開啟就詢問。
        if (isTv && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (savedInstanceState == null) {
            handleShareIntent(intent)
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

            // Android TV 走完全獨立的一套畫面(androidx.tv.material3,見 ui/tv 底下),跟手機/平板版
            // 的 androidx.compose.material3 沒辦法共用同一個 MaterialTheme/Surface——兩邊各自套用
            // 自己那套元件庫的主題,業務邏輯(ConnectItService)仍然是同一份。
            if (isTv) {
                ConnectItTvTheme(themeMode = settings.themeMode) {
                    androidx.tv.material3.Surface(modifier = Modifier.fillMaxSize()) {
                        val service by viewModel.service.collectAsState()
                        val current = service
                        if (current == null) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else {
                            LaunchedEffect(current) {
                                current.exitRequested.collect { finishAndRemoveTask() }
                            }
                            TvAppRoot(service = current)
                        }
                    }
                }
            } else {
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
                            ConnectItApp(service = current, viewModel = viewModel)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    /** 從其他 App 按「分享」→ ConnectIt 進來時,把分享的檔案交給畫面讓使用者選擇要傳給哪台裝置。 */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == ACTION_QUICK_SEND) {
            quickSendPicker.launch(arrayOf("*/*"))
            return
        }
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.streamExtra())
            Intent.ACTION_SEND_MULTIPLE -> intent.streamListExtra()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) {
            // 從分享面板最上排(Direct Share)點了某台裝置:直接傳給那台。
            val targetDeviceId = ShareShortcuts.deviceIdFromShortcutId(intent?.getStringExtra(Intent.EXTRA_SHORTCUT_ID))
            viewModel.setPendingShare(uris, targetDeviceId)
        }
    }

    companion object {
        /** 快速設定磚送來的 action(見 QuickSendTileService)。 */
        const val ACTION_QUICK_SEND = "com.connectit.android.action.QUICK_SEND"
    }

    @Suppress("DEPRECATION")
    private fun Intent.streamExtra(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            getParcelableExtra(Intent.EXTRA_STREAM)
        }

    @Suppress("DEPRECATION")
    private fun Intent.streamListExtra(): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }

    override fun onStart() {
        super.onStart()
        viewModel.bind()
    }

    /** 遙控對方時,實體音量鍵改成調整對方的音量(不調這台)。 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val remote = viewModel.service.value?.remoteControl
        if (remote != null && remote.state.value.phase == RemoteControlPhase.CONTROLLING) {
            val key = when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> RemoteKeys.VOLUME_UP
                KeyEvent.KEYCODE_VOLUME_DOWN -> RemoteKeys.VOLUME_DOWN
                else -> null
            }
            if (key != null) {
                if (event.action == KeyEvent.ACTION_DOWN) remote.sendShortcut(listOf(key))
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        viewModel.unbind()
        super.onStop()
    }
}
