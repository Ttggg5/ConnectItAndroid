package com.connectit.android.net

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * 用本機的假 HTTP 伺服器驗證 [observeControlState]:主機有 `/control/events` 時走推送,
 * 舊版主機(404)時退回輪詢 `/control/state`。
 */
class ControlStateEventsTest {

    private val server = ServerSocket(0)
    private val requestedPaths = CopyOnWriteArrayList<String>()

    @After
    fun tearDown() {
        server.close()
    }

    private fun stateJson(version: Long, path: String?, playing: Boolean, positionMs: Long): String {
        val pathJson = if (path == null) "null" else "\"$path\""
        return """{"Enabled":true,"VideoRelativePath":$pathJson,"IsPlaying":$playing,"PositionMs":$positionMs,""" +
            """"PlaybackRate":1.0,"Volume":1.0,"Muted":false,"Version":$version,"UpdatedAtUtcMs":0}"""
    }

    /** 每個連線讀完請求標頭後交給 [handler] 回應,回應完就關閉。 */
    private fun serve(handler: (path: String, socket: Socket) -> Unit) {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    socket.use {
                        val input = it.getInputStream().bufferedReader()
                        val path = input.readLine().split(' ')[1]
                        while (input.readLine().isNotEmpty()) Unit
                        requestedPaths += path
                        runCatching { handler(path, it) }
                    }
                }
            }
        }
    }

    private fun Socket.write(text: String) {
        getOutputStream().write(text.toByteArray(Charsets.UTF_8))
        getOutputStream().flush()
    }

    @Test
    fun `receives pushed events from control events stream`() = runBlocking {
        serve { path, socket ->
            if (path != "/control/events") {
                socket.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                return@serve
            }
            socket.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n")
            socket.write("retry: 1000\n\n")
            socket.write("data: ${stateJson(1, null, false, 0)}\n\n")
            Thread.sleep(50)
            socket.write(": comment line is ignored\n")
            socket.write("data: ${stateJson(2, "movie.mp4", true, 42_000)}\n\n")
            Thread.sleep(5_000) // 連線維持開著,模擬主機還沒有下一次變更。
        }

        val states = withTimeout(5_000) {
            observeControlState("127.0.0.1", server.localPort).take(2).toList()
        }

        assertEquals(1L, states[0].version)
        assertEquals(null, states[0].videoRelativePath)
        assertEquals(2L, states[1].version)
        assertEquals("movie.mp4", states[1].videoRelativePath)
        assertTrue(states[1].isPlaying)
        assertEquals(42_000L, states[1].positionMs)
        assertFalse(requestedPaths.contains("/control/state"))
    }

    @Test
    fun `falls back to polling control state when host has no events endpoint`() = runBlocking {
        serve { path, socket ->
            if (path == "/control/state") {
                val body = stateJson(7, "old.mp4", false, 1_000)
                socket.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body")
            } else {
                socket.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            }
        }

        val states = withTimeout(5_000) {
            observeControlState("127.0.0.1", server.localPort).take(2).toList()
        }

        assertEquals(listOf(7L, 7L), states.map { it.version })
        assertEquals("old.mp4", states[0].videoRelativePath)
        assertEquals("/control/events", requestedPaths.first())
        assertEquals(1, requestedPaths.count { it == "/control/events" })
    }
}
