package com.connectit.android.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.connectit.android.service.ConnectItService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 只負責綁定/解除綁定 [ConnectItService] 並在 configuration change(例如旋轉螢幕)之間
 * 保留已綁定的實例,實際的裝置/連線/傳輸狀態都直接放在 Service 裡用 StateFlow 暴露,
 * 不在這裡另外複製一份。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _service = MutableStateFlow<ConnectItService?>(null)
    val service: StateFlow<ConnectItService?> = _service.asStateFlow()

    private var isBound = false

    /** 從其他 App「分享」過來、還沒決定要傳給誰的檔案(見 MainActivity.handleShareIntent)。 */
    private val _pendingShare = MutableStateFlow<List<Uri>>(emptyList())
    val pendingShare: StateFlow<List<Uri>> = _pendingShare.asStateFlow()

    /** 從分享面板的 Direct Share 選了某台裝置時,那台的裝置識別碼(直接傳給它,不用再選)。 */
    private val _pendingShareTargetDeviceId = MutableStateFlow<String?>(null)
    val pendingShareTargetDeviceId: StateFlow<String?> = _pendingShareTargetDeviceId.asStateFlow()

    /** 這次程序存活期間是否已經詢問過通知權限,避免每次重組/旋轉螢幕都再問一次。 */
    var notificationPermissionRequested = false

    fun setPendingShare(uris: List<Uri>, targetDeviceId: String? = null) {
        _pendingShareTargetDeviceId.value = targetDeviceId
        _pendingShare.value = uris
    }

    fun clearPendingShare() {
        _pendingShare.value = emptyList()
        _pendingShareTargetDeviceId.value = null
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            _service.value = (binder as ConnectItService.LocalBinder).getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            _service.value = null
        }
    }

    fun bind() {
        if (isBound) return
        val context = getApplication<Application>()
        val intent = Intent(context, ConnectItService::class.java)
        ContextCompat.startForegroundService(context, intent)
        isBound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun unbind() {
        if (!isBound) return
        isBound = false
        runCatching { getApplication<Application>().unbindService(connection) }
    }

    override fun onCleared() {
        unbind()
        super.onCleared()
    }
}
