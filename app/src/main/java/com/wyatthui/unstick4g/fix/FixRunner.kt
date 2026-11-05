package com.wyatthui.unstick4g.fix

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.wyatthui.unstick4g.FixMethodSetting
import com.wyatthui.unstick4g.Notifications
import com.wyatthui.unstick4g.Settings
import com.wyatthui.unstick4g.data.FixAttempt
import com.wyatthui.unstick4g.data.LogDao
import com.wyatthui.unstick4g.data.NetworkEvent
import com.wyatthui.unstick4g.is4g
import com.wyatthui.unstick4g.isOn2g
import com.wyatthui.unstick4g.shizuku.ShizukuState
import com.wyatthui.unstick4g.shizuku.ShizukuStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

enum class FixResult(val shortLabel: String) {
    BACK_ON_4G("Back on 4G ✓"),
    STILL_ON_2G("Still on 2G"),
    REFUSED("Not fixed"),
    FAILED("Failed"),
}

data class FixOutcome(val result: FixResult, val message: String)

sealed interface FixState {
    data object Idle : FixState

    data object Working : FixState

    data class Done(val outcome: FixOutcome, val at: Long) : FixState
}

class FixRunner(
    private val context: Context,
    private val dao: LogDao,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    private val system = ShizukuSystemServices()
    private val flip = NetworkModeFlipKicker(
        system,
        PrefsFlipRecordStore(context),
        SubscriptionManager::getDefaultDataSubscriptionId,
    )
    private val flightMode = FlightModeKicker(system)
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val busy = AtomicBoolean(false)
    private val mutableState = MutableStateFlow<FixState>(FixState.Idle)
    val state: StateFlow<FixState> = mutableState

    fun start() {
        if (!busy.compareAndSet(false, true)) return
        mutableState.value = FixState.Working
        scope.launch {
            val outcome = runCatching { fix() }
                .getOrElse { FixOutcome(FixResult.FAILED, "Fix failed: ${it.message}") }
            Notifications.showFixResult(context, outcome.message)
            mutableState.value = FixState.Done(outcome, System.currentTimeMillis())
            busy.set(false)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun fix(): FixOutcome {
        refusalReason()?.let { return FixOutcome(FixResult.REFUSED, it) }
        val kicker = chooseKicker()
            ?: return FixOutcome(FixResult.REFUSED, "No fix method works for \"${settings.fixMethod.label}\".")
        val startedAt = System.currentTimeMillis()
        val typeBefore = telephony.dataNetworkType
        val secondsOn2g = secondsOn2gBefore(startedAt, dao.eventsUpTo(startedAt, EVENT_LOOKBACK))
        val outcome = try {
            kicker.kick()
            if (cameBackOn4g()) {
                FixOutcome(FixResult.BACK_ON_4G, "Back on 4G ✓")
            } else {
                FixOutcome(FixResult.STILL_ON_2G, "Still on 2G — probably no usable 4G here.")
            }
        } catch (e: Exception) {
            restorePendingFlip()
            FixOutcome(FixResult.FAILED, "Fix failed: ${e.message}")
        }
        dao.insert(
            FixAttempt(
                timestamp = startedAt,
                method = kicker.method.name,
                typeBefore = typeBefore,
                typeAfter = telephony.dataNetworkType,
                secondsOn2gBefore = secondsOn2g,
                result = outcome.result.name,
                durationMs = System.currentTimeMillis() - startedAt,
            ),
        )
        return outcome
    }

    @Suppress("DEPRECATION")
    private fun refusalReason(): String? {
        if (ShizukuState.status.value != ShizukuStatus.READY) {
            return "Shizuku isn't ready. Start it and allow Unstick4G."
        }
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return "Phone permission is missing. Open Unstick4G to grant it."
        }
        if (telephony.callState != TelephonyManager.CALL_STATE_IDLE) {
            return "You're on a call, so nothing was changed."
        }
        val mode = runCatching { flip.originalMode() }.getOrNull()
        if (mode != null && !NetworkModes.isAuto(mode)) {
            return "Network mode is ${NetworkModes.name(mode)}, not auto, so nothing was changed."
        }
        return null
    }

    private fun chooseKicker(): NetworkKicker? = when (settings.fixMethod) {
        FixMethodSetting.AUTO -> listOf(flip, flightMode).firstOrNull { it.isAvailable() }
        FixMethodSetting.FLIP_ONLY -> flip.takeIf { it.isAvailable() }
        FixMethodSetting.FLIGHT_MODE_ONLY -> flightMode.takeIf { it.isAvailable() }
    }

    @SuppressLint("MissingPermission")
    private suspend fun cameBackOn4g(): Boolean {
        repeat(WATCH_SECONDS) {
            if (is4g(telephony.dataNetworkType)) return true
            delay(1_000)
        }
        return is4g(telephony.dataNetworkType)
    }

    fun restorePendingFlip() {
        scope.launch {
            runCatching { flip.restorePending() }
                .onFailure { Notifications.showRestoreFailed(context, it.message.orEmpty()) }
        }
    }

    private companion object {
        const val WATCH_SECONDS = 30
        const val EVENT_LOOKBACK = 500
    }
}

fun secondsOn2gBefore(tapAt: Long, events: List<NetworkEvent>): Long? {
    val newestFirst = events.filter { it.timestamp <= tapAt }.sortedByDescending { it.timestamp }
    if (newestFirst.isEmpty()) return null
    val current2gRun = newestFirst.takeWhile { isOn2g(it.dataType, it.voiceType) }
    if (current2gRun.isEmpty()) return 0
    return (tapAt - current2gRun.last().timestamp) / 1_000
}
