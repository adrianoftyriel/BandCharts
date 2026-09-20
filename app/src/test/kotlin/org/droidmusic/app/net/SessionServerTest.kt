package org.droidmusic.app.net

import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.droidmusic.session.Hello
import org.droidmusic.session.Position
import org.droidmusic.session.Wire
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionServerTest {

    /**
     * The scenario this guards against: a device reconnects while its old socket
     * is still being unwound. The stale handler thread must not remove the new,
     * live connection - nor the follower's presence - from the session, or the
     * band leader stops reaching that phone until it reconnects again.
     *
     * Deterministic because closing a replaced socket is something the server
     * does itself in response to the new Hello, so the old handler's finally
     * block is guaranteed to run *after* the replacement succeeded.
     */
    @Test
    fun `a device that reconnects keeps its live connection and follower entry`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO)
        val server = SessionServer(scope, "Anchor", "Jim", null)
        val port = server.start()

        try {
            val first = Socket("127.0.0.1", port).apply { soTimeout = 3_000 }
            first.sendHello("d1", "Pixel")
            waitUntil("follower joins", { server.state.value.followers.size == 1 })

            // Reconnect: a second socket says the same Hello. The server replaces
            // the first connection and closes its socket.
            val second = Socket("127.0.0.1", port).apply { soTimeout = 3_000 }
            second.sendHello("d1", "Pixel")

            // The server closing the stale socket surfaces as end-of-stream on
            // the old connection, and only then does the old handler thread run
            // its finally block - the exact moment the bug struck.
            waitUntil("the stale socket is closed", {
                first.getInputStream().read() == -1
            })

            // Watch for the stale handler's finally block to try to evict the
            // live connection. With the fix it must not, for longer than it
            // could possibly take to unwind.
            val deadline = System.currentTimeMillis() + 2_000
            while (System.currentTimeMillis() < deadline) {
                assertEquals(
                    "the reconnect must not evict the live follower",
                    1, server.state.value.followers.size,
                )
                delay(10)
            }

            // And the leader can still reach the reconnected follower.
            server.announce(0, "song-1", "T", null, page = 4)
            val position = readPosition(second, page = 4) ?: error("no position reached the follower")
            assertEquals(4, position.page)
            assertEquals("song-1", position.songId)
        } finally {
            server.stop()
            scope.cancel()
        }
    }

    private fun Socket.sendHello(deviceId: String, deviceName: String) {
        val line = Wire.encode(Hello(deviceName = deviceName, deviceId = deviceId))
        getOutputStream().write(line.toByteArray())
        getOutputStream().flush()
    }

    private suspend fun readPosition(socket: Socket, page: Int): Position? {
        val reader = socket.getInputStream().bufferedReader()
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            val line = runCatching { reader.readLine() }.getOrNull() ?: continue
            (Wire.decode(line) as? Position)?.let {
                if (it.page == page) return it
            }
        }
        return null
    }

    private suspend fun waitUntil(stage: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                error("timed out waiting for: $stage")
            }
            delay(20)
        }
    }
}