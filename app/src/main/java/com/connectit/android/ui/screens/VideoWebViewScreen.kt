package com.connectit.android.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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

/** 用內嵌瀏覽器開啟對方影片伺服器的網站,對應 Windows 端用 WebView2 開啟同一個網址的做法——
 * 首頁清單、觀看頁、播放器介面完全是伺服器端提供的網頁,這裡只需要一個能播放影片、支援
 * localStorage(記住播放進度/音量)的瀏覽器容器。 */
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
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
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
                    webView = this
                    loadUrl("http://${server.host}:${server.port}/")
                }
            },
        )
    }
}
