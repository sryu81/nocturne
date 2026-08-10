package com.nocturne.transport

import com.nocturne.protocol.Commands
import com.nocturne.protocol.EkosEvent
import com.nocturne.protocol.EkosEventCodec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Opt-in integration test against a REAL EkosRemote server — not a mock, not
 * a hand-written fixture. Skipped by default (no network dependency, safe in
 * CI); enable with `-DnocturneHwHost=<pi-ip>` (see docs/M3-plan.md §6):
 *
 *   ./gradlew :app:testDebugUnitTest --tests "*HardwareTest" -DnocturneHwHost=10.0.0.43
 *
 * Decodes every reply through the real [EkosEventCodec] — the exact same
 * code `EkosRemoteClient` runs in production. This is the class of test that
 * caught the real bug fixed alongside it: `train_get_all`'s live payload has
 * `profile`/`reducer` as JSON numbers and `adaptiveoptics` as JSON `null`,
 * none of which the original hand-written [com.nocturne.protocol.WireTrain]
 * allowed — every real `train_get_all` reply silently fell back to
 * [EkosEvent.Raw] instead of [EkosEvent.Trains]. A mock server built from the
 * docs would never have caught that; only the real rig's actual bytes did.
 * [com.nocturne.protocol.EkosEventCodecTest] pins that exact payload as a
 * frozen regression fixture so this doesn't depend on hardware being
 * reachable to keep failing closed.
 */
class EkosRemoteHardwareTest {

    private val host = System.getProperty("nocturneHwHost") ?: System.getenv("NOCTURNE_HW_HOST")
    private val port = (System.getProperty("nocturneHwPort") ?: System.getenv("NOCTURNE_HW_PORT"))?.toIntOrNull() ?: 9000

    @Test
    fun `real rig replies decode to typed events, never silently fall back to Raw`() {
        assumeTrue("set -DnocturneHwHost=<pi-ip> to run this against real hardware (skipped by default)", host != null)

        val events = CopyOnWriteArrayList<EkosEvent>()
        val opened = CountDownLatch(1)
        val okHttpClient = OkHttpClient.Builder().pingInterval(Duration.ZERO).build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = opened.countDown()
            override fun onMessage(webSocket: WebSocket, text: String) {
                events.add(EkosEventCodec.decode(text))
            }
        }
        val webSocket = okHttpClient.newWebSocket(Request.Builder().url("ws://$host:$port/message/ekos").build(), listener)

        try {
            assertTrue("socket never opened against $host:$port", opened.await(5, TimeUnit.SECONDS))

            // Give the server's automatic post-connect burst (new_connection_state, get_profiles,
            // train_get_all — confirmed live, not requested by the client) time to arrive.
            Thread.sleep(2_000)

            // These work regardless of whether Ekos itself is online (confirmed live).
            webSocket.send(EkosEventCodec.encode(Commands.ASTRO_SEARCH_OBJECTS))
            webSocket.send(EkosEventCodec.encode(Commands.SCHEDULER_GET_JOBS))
            Thread.sleep(2_000)

            assertTrue("no messages received at all from $host:$port — is EkosRemote actually running?", events.isNotEmpty())

            val raw = events.filterIsInstance<EkosEvent.Raw>()
            // A handful of push types genuinely have no typed case yet —
            // only fail on Raw for the commands this test itself explicitly requested/expects.
            val requestedTypes = setOf("get_profiles", "train_get_all", "new_connection_state", "astro_search_objects", "scheduler_get_jobs")
            val unexpectedRaw = raw.filter { it.type in requestedTypes }
            assertTrue(
                "these known command types decoded to Raw instead of their typed event — the wire " +
                    "shape drifted from what the model assumes: ${unexpectedRaw.map { it.type }}",
                unexpectedRaw.isEmpty(),
            )

            val connectionState = events.filterIsInstance<EkosEvent.NewConnectionState>().firstOrNull()
            assertTrue("no new_connection_state ever arrived", connectionState != null)

            val profiles = events.filterIsInstance<EkosEvent.Profiles>().firstOrNull()
            assertTrue("get_profiles never decoded — real rig always reports at least the built-in Simulators profile", profiles != null)
            assertFalse("get_profiles reported zero profiles — expected at least the built-in Simulators profile", profiles!!.profiles.isEmpty())
        } finally {
            webSocket.close(1000, "test done")
            okHttpClient.dispatcher.executorService.shutdown()
        }
    }
}
