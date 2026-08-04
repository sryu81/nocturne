package com.nocturne.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * M1 session driver. Reproduces the prototype's `data-dc-script` behavior
 * exactly: a 1 s ticker advances `t` (cooler ramp, PA knob easing, mount
 * D-pad slew), on top of the pure local-state methods it shares with
 * [EkosRemoteController] via [AbstractLocalSessionController].
 */
class SimulatedController(private val scope: CoroutineScope) : AbstractLocalSessionController() {

    init {
        scope.launch {
            while (true) {
                delay(1000)
                _state.update { s -> tick(s) }
            }
        }
    }

    private fun tick(s: SimState): SimState {
        val d = s.coolTarget - s.coolNow
        val step = d.coerceIn(-1.4, 1.4)
        val coolNow = if (abs(d) < 0.05) s.coolTarget else s.coolNow + step

        val paAdjust = s.sheet == SheetType.PA && s.paStep == 2 && s.t % PA_SECS[s.paRate] == 0

        // Bench sheet's D-pad — modest per-tick step, not a real slew rate; just needs to visibly move.
        val rateStep = listOf(0.02, 0.05, 0.2, 0.6, 1.2).getOrElse(s.rate) { 0.2 }
        val mountAlt = when (s.slewDir) {
            "N" -> (s.mountAlt + rateStep).coerceIn(0.0, 90.0)
            "S" -> (s.mountAlt - rateStep).coerceIn(0.0, 90.0)
            else -> s.mountAlt
        }
        val mountAz = when (s.slewDir) {
            "E" -> (s.mountAz + rateStep).mod(360.0)
            "W" -> (s.mountAz - rateStep).mod(360.0)
            else -> s.mountAz
        }

        return s.copy(
            t = s.t + 1,
            coolNow = coolNow,
            paAlt = if (paAdjust) ease(s.paAlt) else s.paAlt,
            paAz = if (paAdjust) ease(s.paAz) else s.paAz,
            mountAlt = mountAlt,
            mountAz = mountAz,
        )
    }
}
