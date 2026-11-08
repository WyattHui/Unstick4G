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
    private val blocked: () -> Boolean,
    private val network: () -> NetworkSnapshot?,
    private val keepAwake: (millis: Long) -> Unit,
    private val now: () -> Long,
    private val fix: () -> Unit,
) {
    private var pending: Job? = null

    fun onCarConnectionChanged(connected: Boolean) {
        pending?.cancel()
        if (connected || !enabled()) return
        keepAwake(FIRST_MINUTE_CHECKS.last() + AWAKE_MARGIN_MS)
        val parkedAt = now()
        pending = scope.launch {
            var elapsed = 0L
            suspend fun waitUntil(at: Long) {
                delay(at - elapsed)
                elapsed = at
            }
            for (at in FIRST_MINUTE_CHECKS) {
                waitUntil(at)
                if (!enabled()) return@launch
                if (blocked()) continue
                if (onTwoG()) {
                    fix()
                    break
                }
            }
            for (at in RETRIES) {
                waitUntil(at)
                if (!enabled() || now() - parkedAt > RETRY_DEADLINE_MS) return@launch
                if (!blocked() && onTwoG()) fix()
            }
        }
    }

    private fun onTwoG() = network()?.let { isOn2g(it.dataType, it.voiceType) } == true

    private companion object {
        val FIRST_MINUTE_CHECKS = (1..6).map { it * 10_000L }
        val RETRIES = listOf(5, 15, 45).map { it * 60_000L }
        const val AWAKE_MARGIN_MS = 5_000L
        const val RETRY_DEADLINE_MS = 60 * 60_000L
    }
}
