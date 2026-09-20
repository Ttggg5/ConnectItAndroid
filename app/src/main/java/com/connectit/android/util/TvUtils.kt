package com.connectit.android.util

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration

/** 是否在 Android TV(含電視盒)上執行——用來在同一份 App 裡切換手機/平板版與 TV 版的 UI。 */
fun isTelevision(context: Context): Boolean {
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}
