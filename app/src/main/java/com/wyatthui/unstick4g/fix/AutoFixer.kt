package com.wyatthui.unstick4g.fix

import com.wyatthui.unstick4g.NetworkSnapshot
import com.wyatthui.unstick4g.isOn2g
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AutoFixer(
    private val scope: CoroutineScope,
    private val enabled: () -> Boolean,
    private val network: () -> NetworkSnapshot?,
    private val keepAwake: (millis: Long) -> Unit,
    private val fix: () -> Unit,
) {
    private var pending: Job? = null

    fun onCarConnectionChanged(connected: Boolean) {
        pending?.cancel()
        if (connected || !enabled()) return
        keepAwake(SETTLE_MS + AWAKE_MARGIN_MS)
        pending = scope.launch {
            delay(SETTLE_MS)
            if (enabled() && onTwoG()) fix()
        }
    }

    private fun onTwoG() = network()?.let { isOn2g(it.dataType, it.voiceType) } == true

    private companion object {
        const val SETTLE_MS = 60_000L
        const val AWAKE_MARGIN_MS = 5_000L
    }
}
