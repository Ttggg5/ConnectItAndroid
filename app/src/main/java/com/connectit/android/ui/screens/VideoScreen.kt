package com.connectit.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.connectit.android.model.DiscoveredDevice
import com.connectit.android.service.ConnectItService

@Composable
fun VideoScreen(service: ConnectItService, modifier: Modifier = Modifier, onWatch: (DiscoveredDevice) -> Unit) {
    val servers by service.videoServers.collectAsState()

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("影片") }) },
    ) { padding ->
        if (servers.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text("正在搜尋影片伺服器...", modifier = Modifier.padding(top = 16.dp))
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(16.dp),
        ) {
            items(servers, key = { it.key }) { server ->
                Card(modifier = Modifier.fillMaxWidth(), onClick = { onWatch(server) }) {
                    ListItem(
                        headlineContent = { Text(server.displayName) },
                        supportingContent = { Text("${server.host}:${server.port}") },
                        leadingContent = { Icon(Icons.Filled.PlayCircle, contentDescription = "觀看") },
                    )
                }
            }
        }
    }
}
