package com.wyatthui.unstick4g.fix

import android.telephony.TelephonyManager.NETWORK_TYPE_EDGE
import android.telephony.TelephonyManager.NETWORK_TYPE_GPRS
import android.telephony.TelephonyManager.NETWORK_TYPE_GSM
import android.telephony.TelephonyManager.NETWORK_TYPE_LTE
import android.telephony.TelephonyManager.NETWORK_TYPE_UNKNOWN
import com.wyatthui.unstick4g.NetworkSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

private val EDGE = NetworkSnapshot(NETWORK_TYPE_EDGE, NETWORK_TYPE_GSM)
private val GPRS = NetworkSnapshot(NETWORK_TYPE_GPRS, NETWORK_TYPE_GSM)
private val LTE = NetworkSnapshot(NETWORK_TYPE_LTE, NETWORK_TYPE_LTE)
private val NONE = NetworkSnapshot(NETWORK_TYPE_UNKNOWN, NETWORK_TYPE_UNKNOWN)
private const val SECOND = 1_000L
private const val MINUTE = 60_000L

@OptIn(ExperimentalCoroutinesApi::class)
class StuckWatcherTest {
    private var enabled = true
    private var fixRunning = false
    private var onCall = false
    private val fixes = mutableListOf<FixSource>()
    private var gaveUp = 0

    @Test
    fun `stuck on 2G for 5 minutes while driving is fixed`() = runTest {
        val watcher = drivingOn(EDGE)

        advanceTimeBy(5 * MINUTE - SECOND)
        assertEquals(emptyList<FixSource>(), fixes)
        advanceTimeBy(2 * SECOND)

        assertEquals(listOf(FixSource.STUCK_WHILE_DRIVING), fixes)
        watcher.onNetworkChanged(LTE)
    }

    @Test
    fun `getting 4G back within the 5 minutes cancels the timed Fix`() = runTest {
        val watcher = drivingOn(EDGE)

        advanceTimeBy(3 * MINUTE)
        watcher.onNetworkChanged(LTE)
        advanceUntilIdle()

        assertEquals(emptyList<FixSource>(), fixes)
    }

    @Test
    fun `a no-signal blip inside the 2G spell doesn't restart the 5 minutes`() = runTest {
        val watcher = drivingOn(EDGE)

        advanceTimeBy(2 * MINUTE)
        watcher.onNetworkChanged(NONE)
        advanceTimeBy(10 * SECOND)
        watcher.onNetworkChanged(EDGE)
        advanceTimeBy(3 * MINUTE - 10 * SECOND + SECOND)

        // Long spells in the logs contain short no-signal moments; restarting on them would never reach 5 minutes.
        assertEquals(listOf(FixSource.STUCK_WHILE_DRIVING), fixes)
    }

    @Test
    fun `EDGE changing to GPRS inside the spell still fixes at 5 minutes`() = runTest {
        val watcher = drivingOn(EDGE)

        advanceTimeBy(2 * MINUTE)
        watcher.onNetworkChanged(GPRS)
        advanceTimeBy(3 * MINUTE + SECOND)

        assertEquals(listOf(FixSource.STUCK_WHILE_DRIVING), fixes)
    }

    @Test
    fun `while still stuck it tries again after 10 then 20 minutes`() = runTest {
        drivingOn(EDGE)

        advanceTimeBy(5 * MINUTE + SECOND)
        assertEquals(1, fixes.size)
        advanceTimeBy(10 * MINUTE)
        assertEquals(2, fixes.size)
        advanceTimeBy(20 * MINUTE)
        assertEquals(3, fixes.size)
    }

    @Test
    fun `it stops after 4 timed Fixes in one stuck spell`() = runTest {
        drivingOn(EDGE)

        advanceUntilIdle()

        assertEquals(4, fixes.size)
    }

    @Test
    fun `after 4 tries, more 2G changes in the same spell don't start new tries`() = runTest {
        val watcher = drivingOn(EDGE)
        advanceUntilIdle()

        watcher.onNetworkChanged(GPRS)
        watcher.onNetworkChanged(NONE)
        watcher.onNetworkChanged(EDGE)
        advanceUntilIdle()

        assertEquals(4, fixes.size)
    }

    @Test
    fun `a new stuck spell in the same drive starts again at 5 minutes`() = runTest {
        val watcher = drivingOn(EDGE)
        advanceTimeBy(5 * MINUTE + SECOND)
        watcher.onNetworkChanged(LTE)
        advanceTimeBy(30 * MINUTE)

        watcher.onNetworkChanged(EDGE)
        advanceTimeBy(5 * MINUTE + SECOND)

        assertEquals(2, fixes.size)
    }

    @Test
    fun `with no signal at the 5-minute mark it waits another round without using a try`() = runTest {
        val watcher = drivingOn(EDGE)
        advanceTimeBy(4 * MINUTE)
        watcher.onNetworkChanged(NONE)
        advanceTimeBy(MINUTE + SECOND)

        // Forcing 2G only with no signal at all would cut nothing but still count as one of the 4 tries.
        assertEquals(0, fixes.size)

        watcher.onNetworkChanged(EDGE)
        advanceTimeBy(5 * MINUTE)
        assertEquals(1, fixes.size)
    }

    @Test
    fun `short car Bluetooth drops don't restart the 5 minutes`() = runTest {
        val watcher = drivingOn(EDGE)
        listOf(2 * MINUTE, 2 * MINUTE).forEach { gap ->
            advanceTimeBy(gap)
            watcher.onCarConnectionChanged(false)
            advanceTimeBy(3 * SECOND)
            watcher.onCarConnectionChanged(true)
        }
        advanceTimeBy(MINUTE)

        // A car link that drops every few minutes would otherwise stop the driving Fix from ever firing.
        assertEquals(listOf(FixSource.STUCK_WHILE_DRIVING), fixes)
    }

    @Test
    fun `a car reconnect during the spell doesn't reset the tries`() = runTest {
        val watcher = drivingOn(EDGE)
        advanceTimeBy(5 * MINUTE + SECOND)

        watcher.onCarConnectionChanged(false)
        watcher.onCarConnectionChanged(true)
        advanceTimeBy(5 * MINUTE + SECOND)

        // Flaky car Bluetooth reconnecting every few minutes must not restart the 4 tries.
        assertEquals(1, fixes.size)
        advanceTimeBy(5 * MINUTE)
        assertEquals(2, fixes.size)
    }

    @Test
    fun `a new drive after more than 10 minutes away starts again at 5 minutes`() = runTest {
        val watcher = drivingOn(EDGE)
        advanceTimeBy(5 * MINUTE + SECOND)

        watcher.onCarConnectionChanged(false)
        advanceTimeBy(120 * MINUTE)
        watcher.onCarConnectionChanged(true)
        advanceTimeBy(5 * MINUTE + SECOND)

        // Tries used up yesterday evening must not delay this morning's first Fix.
        assertEquals(2, fixes.size)
    }

    @Test
    fun `switching off and on again within the 5 minutes keeps the timer going`() = runTest {
        drivingOn(EDGE)
        enabled = false
        advanceTimeBy(5 * MINUTE + SECOND)
        assertEquals(0, fixes.size)

        enabled = true
        advanceTimeBy(5 * MINUTE)
        assertEquals(1, fixes.size)
    }

    @Test
    fun `walking on 2G never starts the timed Fix`() = runTest {
        watcher().onNetworkChanged(EDGE)
        advanceTimeBy(60 * MINUTE)

        assertEquals(emptyList<FixSource>(), fixes)
    }

    @Test
    fun `out of the car, the timed Fix doesn't fire`() = runTest {
        val watcher = drivingOn(EDGE)

        advanceTimeBy(2 * MINUTE)
        watcher.onCarConnectionChanged(false)
        advanceTimeBy(60 * MINUTE)
        watcher.onNetworkChanged(LTE)

        assertEquals(emptyList<FixSource>(), fixes)
    }

    @Test
    fun `4G dropping back within 2 minutes of a Fix is re-fixed straight away, walking too`() = runTest {
        val watcher = afterWorkingFix()

        advanceTimeBy(30 * SECOND)
        watcher.onNetworkChanged(EDGE)
        runCurrent()

        assertEquals(listOf(FixSource.FELL_BACK), fixes)
    }

    @Test
    fun `4G dropping back after more than 2 minutes is left alone`() = runTest {
        val watcher = afterWorkingFix()

        advanceTimeBy(3 * MINUTE)
        watcher.onNetworkChanged(EDGE)
        advanceUntilIdle()

        assertEquals(emptyList<FixSource>(), fixes)
    }

    @Test
    fun `re-fixes wait 0, 30 and 60 seconds, then stop with one alert`() = runTest {
        val watcher = afterWorkingFix()

        listOf(0L, 30 * SECOND, 60 * SECOND).forEachIndexed { index, wait ->
            watcher.onNetworkChanged(EDGE)
            if (wait > 0) {
                advanceTimeBy(wait - SECOND)
                assertEquals(index, fixes.size)
            }
            advanceTimeBy(2 * SECOND)
            assertEquals(index + 1, fixes.size)
            watcher.onFixFinished(FixSource.FELL_BACK, FixResult.BACK_ON_4G, currentTime)
            watcher.onNetworkChanged(LTE)
            advanceTimeBy(10 * SECOND)
        }
        watcher.onNetworkChanged(EDGE)
        advanceUntilIdle()

        // In a spot where 4G keeps dropping, endless re-fixes would cut the data again and again.
        assertEquals(3, fixes.size)
        assertEquals(1, gaveUp)
    }

    @Test
    fun `a re-fix that doesn't work ends the chain`() = runTest {
        val watcher = afterWorkingFix()
        watcher.onNetworkChanged(EDGE)
        runCurrent()

        watcher.onFixFinished(FixSource.FELL_BACK, FixResult.STILL_ON_2G, currentTime)
        watcher.onNetworkChanged(LTE)
        watcher.onNetworkChanged(EDGE)
        advanceUntilIdle()

        assertEquals(listOf(FixSource.FELL_BACK), fixes)
    }

    @Test
    fun `4G coming back by itself cancels a waiting re-fix`() = runTest {
        val watcher = afterWorkingFix()
        watcher.onNetworkChanged(EDGE)
        runCurrent()
        watcher.onFixFinished(FixSource.FELL_BACK, FixResult.BACK_ON_4G, currentTime)
        watcher.onNetworkChanged(LTE)

        watcher.onNetworkChanged(EDGE)
        advanceTimeBy(10 * SECOND)
        watcher.onNetworkChanged(LTE)
        advanceUntilIdle()

        assertEquals(listOf(FixSource.FELL_BACK), fixes)
    }

    @Test
    fun `a Fix's own short trip through 2G is not a fall-back`() = runTest {
        val watcher = afterWorkingFix()

        fixRunning = true
        watcher.onNetworkChanged(NONE)
        watcher.onNetworkChanged(EDGE)
        advanceUntilIdle()

        assertEquals(emptyList<FixSource>(), fixes)
    }

    @Test
    fun `a call holds the timed Fix without using a try`() = runTest {
        drivingOn(EDGE)
        onCall = true
        advanceTimeBy(5 * MINUTE + SECOND)

        // A refused Fix during a call would only alert you mid-call.
        assertEquals(0, fixes.size)

        onCall = false
        advanceTimeBy(5 * MINUTE)
        assertEquals(1, fixes.size)
    }

    @Test
    fun `dropping to 2G during a call is not re-fixed`() = runTest {
        val watcher = afterWorkingFix()

        onCall = true
        watcher.onNetworkChanged(EDGE)
        advanceUntilIdle()

        assertEquals(emptyList<FixSource>(), fixes)
    }

    @Test
    fun `after giving up, re-fixes pause for 30 minutes so the alert comes once`() = runTest {
        val watcher = afterWorkingFix()
        repeat(3) {
            watcher.onNetworkChanged(EDGE)
            advanceTimeBy(61 * SECOND)
            watcher.onFixFinished(FixSource.FELL_BACK, FixResult.BACK_ON_4G, currentTime)
            watcher.onNetworkChanged(LTE)
        }
        watcher.onNetworkChanged(EDGE)
        assertEquals(1, gaveUp)

        advanceTimeBy(5 * MINUTE)
        watcher.onFixFinished(FixSource.MANUAL, FixResult.BACK_ON_4G, currentTime)
        watcher.onNetworkChanged(LTE)
        watcher.onNetworkChanged(EDGE)
        advanceUntilIdle()
        assertEquals(3, fixes.size)
        assertEquals(1, gaveUp)

        advanceTimeBy(30 * MINUTE)
        watcher.onFixFinished(FixSource.MANUAL, FixResult.BACK_ON_4G, currentTime)
        watcher.onNetworkChanged(LTE)
        watcher.onNetworkChanged(EDGE)
        runCurrent()
        assertEquals(4, fixes.size)
    }

    @Test
    fun `a waiting re-fix is skipped if the phone has left 2G`() = runTest {
        val watcher = afterWorkingFix()
        watcher.onNetworkChanged(EDGE)
        runCurrent()
        watcher.onFixFinished(FixSource.FELL_BACK, FixResult.BACK_ON_4G, currentTime)
        watcher.onNetworkChanged(LTE)

        watcher.onNetworkChanged(EDGE)
        advanceTimeBy(10 * SECOND)
        watcher.onNetworkChanged(NONE)
        advanceUntilIdle()

        assertEquals(listOf(FixSource.FELL_BACK), fixes)
    }

    @Test
    fun `with the switch off nothing is fixed automatically`() = runTest {
        enabled = false
        val driving = drivingOn(EDGE)
        advanceTimeBy(30 * MINUTE)
        driving.onFixFinished(FixSource.MANUAL, FixResult.BACK_ON_4G, currentTime)
        driving.onNetworkChanged(LTE)
        driving.onNetworkChanged(EDGE)
        advanceTimeBy(60 * MINUTE)
        driving.onNetworkChanged(LTE)

        assertEquals(emptyList<FixSource>(), fixes)
    }

    private fun TestScope.watcher() = StuckWatcher(
        this,
        { enabled },
        { fixRunning },
        { onCall },
        { currentTime },
        { fixes += it },
        { gaveUp++ },
    )

    private fun TestScope.drivingOn(network: NetworkSnapshot) = watcher().apply {
        onNetworkChanged(network)
        onCarConnectionChanged(true)
    }

    private fun TestScope.afterWorkingFix() = watcher().apply {
        onNetworkChanged(EDGE)
        onFixFinished(FixSource.MANUAL, FixResult.BACK_ON_4G, currentTime)
        onNetworkChanged(LTE)
    }
}
