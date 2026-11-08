package com.wyatthui.unstick4g.fix

import com.wyatthui.unstick4g.NetworkSnapshot
import com.wyatthui.unstick4g.is4g
import com.wyatthui.unstick4g.isOn2g
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class StuckWatcher(
    private val scope: CoroutineScope,
    private val enabled: () -> Boolean,
    private val fixRunning: () -> Boolean,
    private val blocked: () -> Boolean,
    private val now: () -> Long,
    private val fix: (FixSource) -> Unit,
    private val gaveUp: () -> Unit,
) {
    private var inCar = false
    private var leftCarAt: Long? = null
    private var lastNetwork: NetworkSnapshot? = null
    private var onFourG = false
    private var inStuckSpell = false
    private var stuckTries = 0
    private var stuckTimer: Job? = null
    private var lastWorkingFixAt: Long? = null
    private var refixesInARow = 0
    private var pendingRefix: Job? = null
    private var pausedUntil = 0L

    fun onCarConnectionChanged(connected: Boolean) {
        inCar = connected
        if (!connected) {
            leftCarAt = now()
            return
        }
        val awayMs = leftCarAt?.let { now() - it } ?: Long.MAX_VALUE
        leftCarAt = null
        if (awayMs > NEW_DRIVE_GAP_MS) {
            stuckTries = 0
            stuckTimer?.cancel()
        }
        if (inStuckSpell && stuckTimer?.isActive != true) startStuckTimer()
    }

    fun onNetworkChanged(network: NetworkSnapshot) {
        lastNetwork = network
        if (is4g(network.dataType)) {
            onFourG = true
            inStuckSpell = false
            stuckTries = 0
            stuckTimer?.cancel()
            pendingRefix?.cancel()
        } else if (isOn2g(network.dataType, network.voiceType)) {
            val fellBack = onFourG
            onFourG = false
            if (!inStuckSpell) {
                inStuckSpell = true
                if (inCar) startStuckTimer()
            }
            if (fellBack && !fixRunning()) onFellBack()
        }
    }

    fun onFixFinished(source: FixSource, result: FixResult, at: Long) {
        if (result != FixResult.BACK_ON_4G) {
            lastWorkingFixAt = null
            return
        }
        lastWorkingFixAt = at
        if (source != FixSource.FELL_BACK) refixesInARow = 0
    }

    private fun onFellBack() {
        val workingFixAt = lastWorkingFixAt ?: return
        lastWorkingFixAt = null
        if (!enabled() || blocked() || isPaused() || now() - workingFixAt > FALL_BACK_WINDOW_MS) return
        if (refixesInARow >= REFIX_WAITS_MS.size) {
            refixesInARow = 0
            pausedUntil = now() + PAUSE_AFTER_GIVING_UP_MS
            gaveUp()
            return
        }
        val wait = REFIX_WAITS_MS[refixesInARow++]
        pendingRefix = scope.launch {
            delay(wait)
            if (enabled() && isOn2gNow() && !blocked() && !isPaused()) fix(FixSource.FELL_BACK)
        }
    }

    private fun startStuckTimer() {
        stuckTimer = scope.launch {
            while (stuckTries < MAX_STUCK_FIXES_PER_SPELL) {
                delay(FIRST_STUCK_WAIT_MS * (1L shl stuckTries))
                if (!enabled() || !inCar || !isOn2gNow() || blocked() || isPaused()) continue
                fix(FixSource.STUCK_WHILE_DRIVING)
                stuckTries++
            }
        }
    }

    private fun isOn2gNow() = lastNetwork?.let { isOn2g(it.dataType, it.voiceType) } == true

    fun isPaused() = now() < pausedUntil

    private companion object {
        const val FIRST_STUCK_WAIT_MS = 5 * 60_000L
        const val MAX_STUCK_FIXES_PER_SPELL = 4
        const val NEW_DRIVE_GAP_MS = 10 * 60_000L
        const val FALL_BACK_WINDOW_MS = 2 * 60_000L
        val REFIX_WAITS_MS = listOf(0L, 30_000L, 60_000L)
        const val PAUSE_AFTER_GIVING_UP_MS = 30 * 60_000L
    }
}
