package com.connectit.android.util

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    /**
     * 這台裝置在區網上的 IPv4 位址(例如 Wi-Fi 的 192.168.x.x),用來組出影片伺服器給瀏覽器開的網址。
     * 略過 loopback、未啟用與虛擬(VPN/行動數據的 rmnet)介面;找不到時回傳 null。
     */
    fun lanIpv4Address(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .sortedBy { iface ->
                // Wi-Fi/乙太網路優先,其次才是其他介面(例如熱點 ap0)。
                when {
                    iface.name.startsWith("wlan") -> 0
                    iface.name.startsWith("eth") -> 1
                    iface.name.startsWith("rmnet") || iface.name.startsWith("tun") -> 9
                    else -> 5
                }
            }
            .flatMap { iface -> iface.inetAddresses.toList().map { iface to it } }
            .firstOrNull { (iface, address) ->
                address is Inet4Address && !address.isLoopbackAddress && address.isSiteLocalAddress &&
                    !iface.name.startsWith("rmnet") && !iface.name.startsWith("tun")
            }
            ?.second?.hostAddress
    }.getOrNull()
}
