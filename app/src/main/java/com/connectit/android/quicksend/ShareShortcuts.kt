package com.connectit.android.quicksend

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.connectit.android.MainActivity
import com.connectit.android.R

/**
 * 分享面板的 Direct Share:最近傳過檔案的裝置會出現在系統分享面板最上排,點了就直接傳給那台
 * (沒連線的話先連線)。用 sharing shortcuts(res/xml/shortcuts.xml 的 share-target),Android 10 以上才有。
 * 捷徑 ID 是「device:裝置識別碼」,分享進來時 MainActivity 從 Intent.EXTRA_SHORTCUT_ID 取回是哪台裝置。
 */
object ShareShortcuts {
    const val CATEGORY = "com.connectit.android.category.SHARE_TO_DEVICE"
    private const val ID_PREFIX = "device:"

    fun deviceIdFromShortcutId(shortcutId: String?): String? = shortcutId?.takeIf { it.startsWith(ID_PREFIX) }?.removePrefix(ID_PREFIX)

    /** 傳送成功後呼叫:把這台裝置放到分享面板最前面(系統會依使用頻率排序、自動淘汰舊的)。 */
    fun push(context: Context, deviceId: String, deviceName: String) {
        val shortcut = ShortcutInfoCompat.Builder(context, ID_PREFIX + deviceId)
            .setShortLabel(deviceName)
            .setLongLabel("用 ConnectIt 傳給 $deviceName")
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setLongLived(true)
            .setCategories(setOf(CATEGORY))
            .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW))
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
    }
}
