package com.wyatthui.unstick4g.fix

import com.wyatthui.unstick4g.NetworkSnapshot
import com.wyatthui.unstick4g.is4g
import com.wyatthui.unstick4g.isOn2g
import com.wyatthui.unstick4g.needsFix
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
    private val liveNetwork: () -> NetworkSnapshot?,
    private val fix: (FixSource) -> Unit,
    private val keepsDropping: () -> Unit,
    private val spellEnded: () -> Unit,
) {
    private var leftCarAt: Long? = null
    private var lastNetwork: NetworkSnapshot? = null
    private var onFourG = false
    private var fourGSince: Long? = null
    private var spellClockFrom: Long? = null
    private var slot = 0
    private var nextTryAt = 0L
    private var ticker: Job? = null
    private var lastFixFinishedAt: Long? = null
    private var lastWorkingFixAt: Long? = null
    private var refixes = 0
    private var fastPhaseOver = false
    private var pendingRefix: Job? = null

    fun onCarConnectionChanged(connected: Boolean) {
        if (connected) onCarConnected() else leftCarAt = now()
        check()
    }

    fun onFixFinished(source: FixSource, result: FixResult, at: Long) {
        lastFixFinishedAt = at
        lastWorkingFixAt = if (result == FixResult.BACK_ON_4G) at else null
        if (source == FixSource.FELL_BACK && result != FixResult.BACK_ON_4G) fastPhaseOver = true
        liveNetwork()?.let { onNetworkChanged(it) }
    }

    fun onNetworkChanged(network: NetworkSnapshot) {
        endSpellIf4gHeld()
        lastNetwork = network
        when {
            is4g(network.dataType) -> onBackOn4g()
            fixRunning() -> Unit
            isOn2g(network.dataType, network.voiceType) -> onOn2g()
            else -> fourGSince = null
        }
        check()
    }

    fun onParked() = startFresh()

    private fun onCarConnected() {
        val awayMs = leftCarAt?.let { now() - it } ?: Long.MAX_VALUE
        leftCarAt = null
        if (awayMs > NEW_DRIVE_GAP_MS) startFresh()
    }

    private fun onBackOn4g() {
        onFourG = true
        if (fourGSince == null) fourGSince = now()
        pendingRefix?.cancel()
    }

    private fun onOn2g() {
        val fellBack = onFourG
        onFourG = false
        fourGSince = null
        if (spellClockFrom == null) startSpell()
        if (fellBack) onFellBack()
    }

    private fun startSpell() {
        restartClock()
        ticker = scope.launch {
            while (spellClockFrom != null) {
                delay((nextTryAt - now()).coerceIn(0, TICK_MS))
                check()
            }
        }
    }

    private fun onFellBack() {
        val workingFixAt = lastWorkingFixAt ?: return
        lastWorkingFixAt = null
        if (fastPhaseOver || !enabled() || blocked() || now() - workingFixAt > FALL_BACK_WINDOW_MS) return
        if (refixes == REFIX_WAITS_MS.size) {
            fastPhaseOver = true
            keepsDropping()
            return
        }
        val wait = REFIX_WAITS_MS[refixes++]
        pendingRefix = scope.launch {
            delay(wait)
            if (enabled() && isOn2gNow() && !blocked()) fix(FixSource.FELL_BACK)
        }
    }

    private fun startFresh() {
        pendingRefix?.cancel()
        refixes = 0
        fastPhaseOver = false
        if (spellClockFrom != null) restartClock()
    }

    private fun check() {
        endSpellIf4gHeld()
        if (spellClockFrom != null && now() >= nextTryAt) tryNow()
    }

    private fun tryNow() {
        when {
            fixJustRan() -> nextSlot()
            pendingRefix?.isActive == true || !enabled() || blocked() || !isOn2gNow() -> nextTryAt = now() + RETRY_MS
            else -> {
                fix(FixSource.STUCK_ON_2G)
                nextSlot()
            }
        }
    }

    private fun fixJustRan() = fixRunning() || lastFixFinishedAt?.let { now() - it < RECENT_FIX_MS } == true

    private fun nextSlot() {
        val from = spellClockFrom ?: return
        do slot++ while (from + slotOffsetMs(slot) <= now())
        nextTryAt = from + slotOffsetMs(slot)
    }

    private fun endSpellIf4gHeld() {
        val since = fourGSince ?: return
        if (spellClockFrom == null || now() - since < FOUR_G_HOLD_MS) return
        spellClockFrom = null
        ticker?.cancel()
        refixes = 0
        fastPhaseOver = false
        spellEnded()
    }

    private fun restartClock() {
        spellClockFrom = now()
        slot = 0
        nextTryAt = now() + slotOffsetMs(0)
    }

    private fun slotOffsetMs(slot: Int) =
        SLOTS_MS.getOrElse(slot) { SLOTS_MS.last() + (slot - SLOTS_MS.lastIndex) * LATE_TRY_GAP_MS }

    private fun isOn2gNow() = lastNetwork?.let { needsFix(it.dataType, it.voiceType) } == true

    private companion object {
        const val MINUTE_MS = 60_000L
        val SLOTS_MS = listOf(2L, 5, 10, 15, 30, 60).map { it * MINUTE_MS }
        const val LATE_TRY_GAP_MS = 60 * MINUTE_MS
        const val RETRY_MS = 2 * MINUTE_MS
        const val RECENT_FIX_MS = MINUTE_MS
        const val TICK_MS = 30_000L
        const val FOUR_G_HOLD_MS = 5 * MINUTE_MS
        const val NEW_DRIVE_GAP_MS = 10 * MINUTE_MS
        const val FALL_BACK_WINDOW_MS = 2 * MINUTE_MS
        val REFIX_WAITS_MS = listOf(0L, 30_000L, 60_000L)
    }
}
