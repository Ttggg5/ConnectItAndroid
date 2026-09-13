package com.connectit.android.ui.screens

import android.annotation.SuppressLint
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.connectit.android.model.DiscoveredDevice

/**
 * 用內嵌瀏覽器開啟對方影片伺服器的網站,對應 Windows 端用 WebView2 開啟同一個網址的做法——
 * 首頁清單、觀看頁、播放器介面(排序、記住播放進度/音量、自動播放下一部等)完全是伺服器端
 * 提供的網頁,不用在 Android 端另外重刻一份。
 *
 * 已知限制:WebView 內嵌在 Compose 的 AndroidView 裡播放 `<video>` 時,在部分裝置上會出現
 * 「聲音正常但畫面全黑」的相容性問題(在一台 Pixel 8 上實測重現過,`setLayerType(HARDWARE)`
 * 也無法解決)。這裡選擇接受這個風險以換取跟 Windows 網頁版一致的完整播放器功能;如果之後
 * 想要保證畫面一定顯示,可以參考 git 歷史裡 ExoPlayer + TextureView 的原生播放器實作
 * (commit b017a7a)換回來。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VideoWebViewScreen(server: DiscoveredDevice, onBack: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }

    BackHandler {
        val view = webView
        if (view?.canGoBack() == true) view.goBack() else onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(server.displayName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(padding),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    webViewClient = WebViewClient()
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    webView = this
                    loadUrl("http://${server.host}:${server.port}/")
                }
            },
        )
    }
}
