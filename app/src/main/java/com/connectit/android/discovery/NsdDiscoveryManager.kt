package com.connectit.android.discovery

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.connectit.android.model.DiscoveredDevice
import java.net.InetAddress
import java.util.ArrayDeque
import java.util.UUID

private const val TAG = "NsdDiscoveryManager"

/**
 * 用標準 mDNS/DNS-SD 在區網廣播/搜尋裝置,包在 Android [NsdManager] 之上。
 * 對應 Windows 端的 MdnsDiscoveryService.cs——因為兩邊都是標準協定(RFC 6762/6763),
 * 用同一個 service type 就能互相探索、互相連線。
 *
 * 一個實例只負責一種用途(裝置配對或影片伺服器)的一組 service type,兩種用途各自建立
 * 一個獨立的實例,彼此互不干擾。
 */
class NsdDiscoveryManager(
    private val nsdManager: NsdManager,
    private val serviceType: String,
) {
    var onDeviceDiscovered: ((DiscoveredDevice) -> Unit)? = null
    var onDeviceRemoved: ((String) -> Unit)? = null
    var onStatusChanged: ((String) -> Unit)? = null

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /** Android 註冊服務時可能因為撞名而自動改名,一律以 onServiceRegistered 回報的實際名稱為準。 */
    @Volatile
    private var selfServiceName: String? = null

    // resolveService() 同一時間只能有一個進行中的呼叫,用一個簡單佇列把待解析的服務排隊處理。
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val resolveLock = Any()

    fun startDiscovery() {
        if (discoveryListener != null) return

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                onStatusChanged?.invoke("mDNS 已啟動,正在搜尋裝置...")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (isSelf(service.serviceName)) return
                enqueueResolve(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                onDeviceRemoved?.invoke(service.serviceName)
            }

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                onStatusChanged?.invoke("搜尋裝置失敗(錯誤碼 $errorCode)。")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                runCatching { nsdManager.stopServiceDiscovery(this) }
            }
        }

        discoveryListener = listener
        runCatching { nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { onStatusChanged?.invoke("搜尋裝置失敗:${it.message}") }
    }

    fun stopDiscovery() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        runCatching { nsdManager.stopServiceDiscovery(listener) }
        synchronized(resolveLock) {
            resolveQueue.clear()
            resolving = false
        }
    }

    /** 手動重新觸發搜尋(NsdManager 本身沒有「重新查詢」API,做法是重啟一次 discovery)。 */
    fun refresh() {
        if (discoveryListener == null) return
        stopDiscovery()
        startDiscovery()
    }

    /** 把這台裝置廣播出去。每次呼叫都用新的短碼後綴產生新的實體名稱,避免重新廣播(例如換了連接埠)
     * 時跟前一次殘留的註冊/快取記錄混在一起。 */
    fun advertise(deviceName: String, port: Int) {
        stopAdvertising()

        val friendlyName = deviceName.ifBlank { Build.MODEL }.replace('.', '-')
        val suffix = UUID.randomUUID().toString().replace("-", "").take(4)
        val instanceName = "$friendlyName-$suffix"

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = instanceName
            this.serviceType = this@NsdDiscoveryManager.serviceType
            this.port = port
            setAttribute("name", friendlyName)
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                selfServiceName = info.serviceName
                onStatusChanged?.invoke("已廣播本機服務「${info.serviceName}」,連接埠 $port。")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                onStatusChanged?.invoke("廣播本機服務失敗(錯誤碼 $errorCode)。")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                onStatusChanged?.invoke("已停止廣播本機服務。")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }

        registrationListener = listener
        runCatching { nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { onStatusChanged?.invoke("廣播本機服務失敗:${it.message}") }
    }

    fun stopAdvertising() {
        val listener = registrationListener ?: return
        registrationListener = null
        selfServiceName = null
        runCatching { nsdManager.unregisterService(listener) }
    }

    fun teardown() {
        stopDiscovery()
        stopAdvertising()
    }

    private fun isSelf(serviceName: String): Boolean =
        selfServiceName?.equals(serviceName, ignoreCase = true) == true

    private fun enqueueResolve(service: NsdServiceInfo) {
        synchronized(resolveLock) {
            resolveQueue.add(service)
            if (resolving) return
            resolving = true
        }
        resolveNext()
    }

    private fun resolveNext() {
        val next: NsdServiceInfo
        synchronized(resolveLock) {
            next = resolveQueue.poll() ?: run {
                resolving = false
                return
            }
        }

        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "resolve 失敗:${info.serviceName} ($errorCode)")
                resolveNext()
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                if (!isSelf(info.serviceName)) {
                    emit(info)
                }
                resolveNext()
            }
        }

        runCatching { nsdManager.resolveService(next, listener) }
            .onFailure { resolveNext() }
    }

    private fun emit(info: NsdServiceInfo) {
        val address: InetAddress = info.host ?: return
        val friendlyName = readFriendlyNameAttribute(info)

        onDeviceDiscovered?.invoke(
            DiscoveredDevice(
                instanceName = info.serviceName,
                host = address.hostAddress ?: address.toString(),
                port = info.port,
                friendlyName = friendlyName,
            )
        )
    }

    private fun readFriendlyNameAttribute(info: NsdServiceInfo): String? = runCatching {
        info.attributes["name"]?.let { String(it, Charsets.UTF_8) }
    }.getOrNull()

    companion object {
        const val DEVICE_SERVICE_TYPE = "_connectit._tcp."
        const val VIDEO_SERVICE_TYPE = "_connectit-video._tcp."
    }
}
