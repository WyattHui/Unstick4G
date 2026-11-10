package com.wyatthui.unstick4g.fix

import com.wyatthui.unstick4g.NetworkSnapshot
import com.wyatthui.unstick4g.needsFix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AutoFixer(
    private val scope: CoroutineScope,
    private val enabled: () -> Boolean,
    private val blocked: () -> Boolean,
    private val network: () -> NetworkSnapshot?,
    private val keepAwake: (millis: Long) -> Unit,
    private val parked: () -> Unit,
    private val fix: () -> Unit,
) {
    private var pending: Job? = null

    fun onCarConnectionChanged(connected: Boolean) {
        pending?.cancel()
        if (connected || !enabled()) return
        keepAwake(CHECK_INTERVAL_MS * CHECKS + AWAKE_MARGIN_MS)
        pending = scope.launch {
            for (check in 1..CHECKS) {
                delay(CHECK_INTERVAL_MS)
                if (!enabled()) return@launch
                if (!blocked() && onTwoG()) {
                    fix()
                    break
                }
            }
            parked()
        }
    }

    private fun onTwoG() = network()?.let { needsFix(it.dataType, it.voiceType) } == true

    private companion object {
        const val CHECK_INTERVAL_MS = 10_000L
        const val CHECKS = 6
        const val AWAKE_MARGIN_MS = 5_000L
    }
}
