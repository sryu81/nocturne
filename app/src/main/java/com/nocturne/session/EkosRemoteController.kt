package com.nocturne.session

import com.nocturne.protocol.EkosEvent
import com.nocturne.transport.EkosRemoteClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * M2 session driver for a real EkosRemote connection. Runs no ticker — `t`
 * and every simulator-only field stay frozen at [SimState]'s defaults;
 * real telemetry arrives instead as [EkosEvent] pushes on [client], applied
 * here to the wire-mirror fields only (`wireCaptureStatus` etc. — see
 * [SimState]'s doc comment on those). The ~85 pure local-UI methods (sheet
 * nav, job/block editing, prefs, ...) are inherited unchanged from
 * [AbstractLocalSessionController]; none of them send a real wire command
 * in M2 (see plan §4 "method-scoping decision") — they're optimistic local
 * updates only, same behavior as the simulator, until M3 wires them to
 * `client.sendCommand(...)`.
 *
 * Connection lifecycle ([ConnectionState]/reconnect/backoff) lives entirely
 * in [client], not here — [SessionViewModel] reads `client.connectionStatus`
 * directly for the connect screen and reconnect banner, so `NewConnectionState`
 * below only logs through [SimState] as much as it always did (nothing —
 * intentionally not mirrored, see below).
 */
class EkosRemoteController(
    private val client: EkosRemoteClient,
    scope: CoroutineScope,
) : AbstractLocalSessionController() {

    init {
        scope.launch {
            client.events.collect { event -> _state.update { s -> applyEvent(s, event) } }
        }
        client.connect()
    }

    private fun applyEvent(s: SimState, event: EkosEvent): SimState = when (event) {
        // Connection progress is consumed straight from client.connectionStatus by the
        // connect screen / reconnect banner — not funneled through SimState at all.
        is EkosEvent.NewConnectionState -> s
        is EkosEvent.NewCaptureState -> s.copy(wireCaptureStatus = event.status)
        is EkosEvent.NewMountState -> s.copy(
            wireMountStatus = event.status,
            wireMountTarget = event.target,
            wireMountSlewRate = event.slewRate,
            wireMountPierSide = event.pierSide,
        )
        is EkosEvent.NewFocusState -> s.copy(wireFocusStatus = event.status)
        is EkosEvent.NewGuideState -> s.copy(wireGuideStatus = event.status)
        is EkosEvent.NewAlignState -> s.copy(wireAlignStatus = event.status)
        is EkosEvent.NewPolarState -> s.copy(wirePolarStage = event.stage)
        // get_profiles/get_devices and anything else unmodeled — M3's job to type and apply.
        is EkosEvent.Raw -> s
    }
}
