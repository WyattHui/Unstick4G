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
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

private val EDGE = NetworkSnapshot(NETWORK_TYPE_EDGE, NETWORK_TYPE_GSM)
private val GPRS = NetworkSnapshot(NETWORK_TYPE_GPRS, NETWORK_TYPE_GSM)
private val LTE = NetworkSnapshot(NETWORK_TYPE_LTE, NETWORK_TYPE_LTE)
private val NONE = NetworkSnapshot(NETWORK_TYPE_UNKNOWN, NETWORK_TYPE_UNKNOWN)
private val LTE_DATA_GSM_VOICE = NetworkSnapshot(NETWORK_TYPE_LTE, NETWORK_TYPE_GSM)
private const val SECOND = 1_000L
private const val MINUTE = 60_000L

@OptIn(ExperimentalCoroutinesApi::class)
class StuckWatcherTest {
    private var enabled = true
    private var fixRunning = false
    private var onCall = false
    private var slept = 0L
    private var live: NetworkSnapshot? = null
    private val fixes = mutableListOf<FixSource>()
    private val fixTimes = mutableListOf<Long>()
    private var keepsDroppingAlerts = 0
    private var alertsCleared = 0

    @Test
    fun `stuck on 2G for 2 minutes is fixed`() = runTest {
        stuckOn(EDGE)

        advanceTimeBy(2 * MINUTE - SECOND)
        assertEquals(emptyList<FixSource>(), fixes)
        advanceTimeBy(2 * SECOND)

        assertEquals(listOf(FixSource.STUCK_ON_2G), fixes)
    }

    @Test
    fun `getting 4G back within the 2 minutes means no Fix`() = runTest {
        val watcher = stuckOn(EDGE)

        advanceTimeBy(MINUTE)
        watcher.onNetworkChanged(LTE)
        advanceTimeBy(10 * MINUTE)

        assertEquals(emptyList<FixSource>(), fixes)
    }

    @Test
    fun `while still stuck it tries at 2, 5, 10, 15, 30 and 60 minutes, then every hour`() = runTest {
        val watcher = stuckOn(EDGE)
        val minutes = listOf(2, 5, 10, 15, 30, 60, 120, 180)

        failTries(watcher, minutes)

        // Early tries catch 4G coming back soon; hourly ones keep trying without cutting data every few minutes.
        assertEquals(minutes.map { it * MINUTE }, fixTimes)
    }

    @Test
    fun `brief 4G after a Fix doesn't restart the schedule`() = runTest {
        val watcher = stuckOn(EDGE)
        advanceTimeBy(2 * MINUTE + SECOND)
        fixWorks(watcher)

        advanceTimeBy(2 * MINUTE + 30 * SECOND)
        watcher.onNetworkChanged(EDGE)
        advanceTimeBy(30 * SECOND)

        // If brief 4G reset the schedule, a place where 4G comes and goes would get a Fix every few minutes forever.
        assertEquals(listOf(2 * MINUTE, 5 * MINUTE), fixTimes)
    }

    @Test
    fun `4G that holds 5 minutes ends the spell, so the next drop starts again at 2 minutes`() = runTest {
        val watcher = stuckOn(EDGE)
        advanceTimeBy(2 * MINUTE + SECOND)
        fixWorks(watcher)

        advanceTimeBy(7 * MINUTE)
        watcher.onNetworkChanged(EDGE)
        advanceTimeBy(2 * MINUTE + SECOND)

        assertEquals(listOf(2 * MINUTE, 11 * MINUTE + SECOND), fixTimes)
    }

    @Test
    fun `4G that holds 5 minutes clears the automatic alerts`() = runTest {
        val watcher = stuckOn(EDGE)
        advanceTimeBy(2 * MINUTE + SECOND)
        fixFails(watcher)
        watcher.onNetworkChanged(LTE)

        advanceTimeBy(4 * MINUTE)
        assertEquals(0, alertsCleared)
        advanceTimeBy(2 * MINUTE)

        // An old alert left showing would make the next spell's failure arrive without a sound.
        assertEquals(1, alertsCleared)
    }

    @Test
    fun `a try that finds brief 4G checks again 2 minutes later`() = runTest {
        val watcher = stuckOn(EDGE)
        advanceTimeBy(2 * MINUTE + SECOND)
        fixWorks(watcher)

        advanceTimeBy(4 * MINUTE)
        watcher.onNetworkChanged(EDGE)
        advanceTimeBy(MINUTE)

        // Late in a spell the next slot is an hour away, so a 10-second flicker of 4G must not use up the try.
        assertEquals(listOf(2 * MINUTE, 7 * MINUTE), fixTimes)
    }

    @Test
    fun `a call holds the try, and it checks again 2 minutes later`() = runTest {
        stuckOn(EDGE)
        onCall = true
        advanceTimeBy(2 * MINUTE + SECOND)

        // A refused Fix during a call would only alert you mid-call.
        assertEquals(0, fixes.size)

        onCall = false
        advanceTimeBy(2 * MINUTE)
        assertEquals(listOf(4 * MINUTE), fixTimes)
    }

    @Test
    fun `with no signal at a try it checks again 2 minutes later`() = runTest {
        val watcher = stuckOn(EDGE)

        advanceTimeBy(MINUTE)
        watcher.onNetworkChanged(NONE)
        advanceTimeBy(2 * MINUTE)
        watcher.onNetworkChanged(EDGE)
        advanceTimeBy(MINUTE + SECOND)

        assertEquals(listOf(4 * MINUTE), fixTimes)
    }

    @Test
    fun `a Fix that just finished counts as the try`() = runTest {
        val watcher = stuckOn(EDGE)

        advanceTimeBy(MINUTE + 20 * SECOND)
        fixRunning = true
        advanceTimeBy(10 * SECOND)
        fixFails(watcher, FixSource.MANUAL)
        advanceTimeBy(4 * MINUTE)

        // Two Fixes back to back would cut data twice for nothing.
        assertEquals(listOf(5 * MINUTE), fixTimes)
    }

    @Test
    fun `EDGE changing to GPRS and no-signal blips don't restart the clock`() = runTest {
        val watcher = stuckOn(EDGE)

        advanceTimeBy(MINUTE)
        watcher.onNetworkChanged(NONE)
        watcher.onNetworkChanged(GPRS)
        advanceTimeBy(MINUTE + SECOND)

        assertEquals(listOf(2 * MINUTE), fixTimes)
    }

    @Test
    fun `parking restarts the clock, even after a parking Fix that failed`() = runTest {
        val watcher = stuckOn(EDGE)
        failTries(watcher, listOf(2, 5, 10, 15, 30, 60))
        advanceTimeBy(9 * MINUTE)

        fixRunning = true
        watcher.onParked()
        advanceTimeBy(20 * SECOND)
        fixFails(watcher, FixSource.CAR_DISCONNECT)
        advanceTimeBy(2 * MINUTE)

        // A new place may have 4G, so waiting up to an hour for the next try would leave you on 2G after you arrive.
        assertEquals(71 * MINUTE + SECOND, fixTimes.last())
    }

    @Test
    fun `getting in the car after more than 10 minutes away restarts the clock`() = runTest {
        val watcher = stuckOn(EDGE)
        failTries(watcher, listOf(2, 5, 10, 15, 30, 60))

        watcher.onCarConnectionChanged(false)
        advanceTimeBy(15 * MINUTE)
        watcher.onCarConnectionChanged(true)
        advanceTimeBy(2 * MINUTE + SECOND)

        assertEquals(77 * MINUTE + SECOND, fixTimes.last())
    }

    @Test
    fun `short car Bluetooth drops don't restart the clock`() = runTest {
        val watcher = stuckOn(EDGE)
        failTries(watcher, listOf(2, 5, 10, 15, 30, 60))

        watcher.onCarConnectionChanged(false)
        advanceTimeBy(30 * SECOND)
        watcher.onCarConnectionChanged(true)
        advanceTimeBy(10 * MINUTE)

        // A drop under a minute is not parking, so it must not turn the hourly tries back into tries every few minutes.
        assertEquals(60 * MINUTE, fixTimes.last())
    }

    @Test
    fun `after the phone slept through tries, it tries once soon after waking`() = runTest {
        val watcher = stuckOn(EDGE)
        advanceTimeBy(MINUTE + 10 * SECOND)

        slept = 40 * MINUTE
        advanceTimeBy(30 * SECOND)

        // Android's timers stop counting while the phone sleeps, so a plain wait could make this try hours late.
        assertEquals(listOf(MINUTE + 30 * SECOND), fixTimes)

        fixFails(watcher)
        advanceTimeBy(15 * MINUTE)
        assertEquals(1, fixes.size)
        advanceTimeBy(4 * MINUTE)
        assertEquals(20 * MINUTE, fixTimes.last())
    }

    @Test
    fun `a network change after sleep runs an overdue try straight away`() = runTest {
        val watcher = stuckOn(EDGE)
        advanceTimeBy(MINUTE + 10 * SECOND)

        slept = 40 * MINUTE
        watcher.onNetworkChanged(GPRS)

        assertEquals(listOf(FixSource.STUCK_ON_2G), fixes)
    }

    @Test
    fun `with the switch off nothing is fixed, and switching back on works mid-spell`() = runTest {
        enabled = false
        stuckOn(EDGE)

        advanceTimeBy(30 * MINUTE)
        assertEquals(0, fixes.size)

        enabled = true
        advanceTimeBy(2 * MINUTE + SECOND)
        assertEquals(listOf(FixSource.STUCK_ON_2G), fixes)
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
        runCurrent()

        assertEquals(emptyList<FixSource>(), refixes())
    }

    @Test
    fun `re-fixes wait 0, 30 and 60 seconds, then hand over to the slow tries with one alert`() = runTest {
        val watcher = afterWorkingFix()

        val waits = dropBackUntilHandover(watcher)

        assertEquals(listOf(0L, 30 * SECOND, 60 * SECOND), waits)
        assertEquals(1, keepsDroppingAlerts)
        // The slow tries that fell during the re-fixes must not add Fixes of their own.
        assertEquals(refixes(), fixes)

        advanceTimeBy(5 * MINUTE)
        assertEquals(FixSource.STUCK_ON_2G, fixes.last())
        fixWorks(watcher)
        advanceTimeBy(20 * SECOND)
        watcher.onNetworkChanged(EDGE)
        advanceTimeBy(61 * SECOND)

        // Re-fixing forever where 4G can't hold would cut data every minute; the slow tries take over instead.
        assertEquals(3, refixes().size)
        assertEquals(1, keepsDroppingAlerts)
    }

    @Test
    fun `a slow try that falls while a re-fix is waiting doesn't add a second Fix`() = runTest {
        val watcher = afterWorkingFix()
        advanceTimeBy(20 * SECOND)
        watcher.onNetworkChanged(EDGE)
        runCurrent()
        advanceTimeBy(SECOND)
        fixWorks(watcher)

        advanceTimeBy(79 * SECOND)
        watcher.onNetworkChanged(EDGE)
        advanceTimeBy(31 * SECOND)

        // The 2-minute try falls inside the 30-second re-fix wait; both firing would cut data twice.
        assertEquals(listOf(FixSource.FELL_BACK, FixSource.FELL_BACK), fixes)
    }

    @Test
    fun `a re-fix that doesn't work ends the fast phase, with no keeps-dropping alert`() = runTest {
        val watcher = afterWorkingFix()
        advanceTimeBy(20 * SECOND)
        watcher.onNetworkChanged(EDGE)
        runCurrent()
        fixFails(watcher)

        advanceTimeBy(MINUTE + 41 * SECOND)
        fixWorks(watcher)
        advanceTimeBy(20 * SECOND)
        watcher.onNetworkChanged(EDGE)
        advanceTimeBy(61 * SECOND)

        // Re-fixes resuming after the slow try would bring a second burst after a quiet stretch.
        assertEquals(listOf(FixSource.FELL_BACK, FixSource.STUCK_ON_2G), fixes)
        // That Fix already posted "Still on 2G", so a "keeps dropping" alert would only repeat it.
        assertEquals(0, keepsDroppingAlerts)
    }

    @Test
    fun `4G coming back by itself cancels a waiting re-fix`() = runTest {
        val watcher = afterWorkingFix()
        advanceTimeBy(20 * SECOND)
        watcher.onNetworkChanged(EDGE)
        runCurrent()
        fixWorks(watcher)

        advanceTimeBy(20 * SECOND)
        watcher.onNetworkChanged(EDGE)
        advanceTimeBy(10 * SECOND)
        watcher.onNetworkChanged(LTE)
        advanceTimeBy(MINUTE)

        assertEquals(1, refixes().size)
    }

    @Test
    fun `a waiting re-fix is skipped if the phone has left 2G`() = runTest {
        val watcher = afterWorkingFix()
        advanceTimeBy(20 * SECOND)
        watcher.onNetworkChanged(EDGE)
        runCurrent()
        fixWorks(watcher)

        advanceTimeBy(20 * SECOND)
        watcher.onNetworkChanged(EDGE)
        advanceTimeBy(10 * SECOND)
        watcher.onNetworkChanged(NONE)
        advanceTimeBy(30 * SECOND)

        assertEquals(1, refixes().size)
    }

    @Test
    fun `dropping to 2G during a call is not re-fixed`() = runTest {
        val watcher = afterWorkingFix()

        onCall = true
        watcher.onNetworkChanged(EDGE)
        advanceTimeBy(MINUTE)

        assertEquals(emptyList<FixSource>(), refixes())
    }

    @Test
    fun `a Fix's own short trip through 2G is not a drop back`() = runTest {
        val watcher = afterWorkingFix()

        fixRunning = true
        watcher.onNetworkChanged(NONE)
        watcher.onNetworkChanged(EDGE)
        advanceTimeBy(MINUTE)

        assertEquals(emptyList<FixSource>(), refixes())
    }

    @Test
    fun `a Fix tapped on 4G that leaves the phone on 2G starts the stuck tries`() = runTest {
        val watcher = watcher().apply { onNetworkChanged(LTE) }

        fixRunning = true
        watcher.onNetworkChanged(EDGE)
        live = EDGE
        fixFails(watcher, FixSource.MANUAL)
        advanceTimeBy(2 * MINUTE + SECOND)

        // The logger only reports changes, so this 2G, seen while the Fix ran, is never reported again.
        assertEquals(listOf(FixSource.STUCK_ON_2G), fixes)
    }

    @Test
    fun `4G dropping just before a Fix reports done is still re-fixed`() = runTest {
        val watcher = stuckOn(EDGE)
        advanceTimeBy(2 * MINUTE + SECOND)

        watcher.onNetworkChanged(LTE)
        watcher.onNetworkChanged(EDGE)
        live = EDGE
        fixRunning = false
        watcher.onFixFinished(FixSource.STUCK_ON_2G, FixResult.BACK_ON_4G, currentTime + slept)
        runCurrent()

        assertEquals(listOf(FixSource.STUCK_ON_2G, FixSource.FELL_BACK), fixes)
    }

    @Test
    fun `a Fix that saw 4G before Android reported it is not re-fixed`() = runTest {
        val watcher = watcher().apply { onNetworkChanged(LTE) }

        fixRunning = true
        watcher.onNetworkChanged(EDGE)
        live = LTE
        fixRunning = false
        watcher.onFixFinished(FixSource.MANUAL, FixResult.BACK_ON_4G, currentTime + slept)
        runCurrent()

        // The 2G event was the Fix's own trip; trusting it would cut data again right after a Fix that worked.
        assertEquals(emptyList<FixSource>(), fixes)
    }

    @Test
    fun `4G data with GSM voice is not stuck`() = runTest {
        val watcher = stuckOn(EDGE)

        advanceTimeBy(MINUTE)
        watcher.onNetworkChanged(LTE_DATA_GSM_VOICE)
        advanceTimeBy(MINUTE + SECOND)

        // Data is what you notice; flipping a phone that already has 4G data would cut it for nothing.
        assertEquals(emptyList<FixSource>(), fixes)
    }

    @Test
    fun `4G that holds 5 minutes gives the next spell its re-fixes back`() = runTest {
        val watcher = afterWorkingFix()
        dropBackUntilHandover(watcher)

        watcher.onNetworkChanged(LTE)
        advanceTimeBy(6 * MINUTE)
        watcher.onNetworkChanged(EDGE)
        advanceTimeBy(2 * MINUTE + SECOND)
        fixWorks(watcher)
        advanceTimeBy(20 * SECOND)
        watcher.onNetworkChanged(EDGE)
        runCurrent()

        assertEquals(4, refixes().size)
    }

    @Test
    fun `parking cancels a waiting re-fix`() = runTest {
        val watcher = afterWorkingFix()
        advanceTimeBy(20 * SECOND)
        watcher.onNetworkChanged(EDGE)
        runCurrent()
        fixWorks(watcher)

        advanceTimeBy(20 * SECOND)
        watcher.onNetworkChanged(EDGE)
        watcher.onParked()
        advanceTimeBy(31 * SECOND)

        // The parking Fix covers the new place; the old place's re-fix would cut data a second time.
        assertEquals(1, refixes().size)
    }

    @Test
    fun `parking gives the re-fixes back`() = runTest {
        val watcher = afterWorkingFix()
        dropBackUntilHandover(watcher)

        watcher.onParked()
        advanceTimeBy(2 * MINUTE + SECOND)
        fixWorks(watcher)
        advanceTimeBy(20 * SECOND)
        watcher.onNetworkChanged(EDGE)
        runCurrent()

        // A new place deserves the quick re-fixes again, even if the last place used them up.
        assertEquals(4, refixes().size)
    }

    @Test
    fun `with the switch off nothing is re-fixed`() = runTest {
        enabled = false
        val watcher = afterWorkingFix()

        advanceTimeBy(30 * SECOND)
        watcher.onNetworkChanged(EDGE)
        advanceTimeBy(10 * MINUTE)

        assertEquals(emptyList<FixSource>(), fixes)
    }

    private fun refixes() = fixes.filter { it == FixSource.FELL_BACK }

    private fun TestScope.stuckOn(network: NetworkSnapshot) = watcher().apply { onNetworkChanged(network) }

    private fun TestScope.afterWorkingFix() = watcher().apply {
        onNetworkChanged(EDGE)
        onNetworkChanged(LTE)
        onFixFinished(FixSource.MANUAL, FixResult.BACK_ON_4G, currentTime + slept)
    }

    private fun TestScope.failTries(watcher: StuckWatcher, minutes: List<Int>) {
        for (minute in minutes) {
            advanceTimeBy(minute * MINUTE - currentTime + SECOND)
            fixFails(watcher)
        }
    }

    private fun TestScope.dropBackUntilHandover(watcher: StuckWatcher): List<Long> {
        val waits = mutableListOf<Long>()
        repeat(4) {
            advanceTimeBy(20 * SECOND)
            watcher.onNetworkChanged(EDGE)
            val droppedAt = currentTime
            val fixesBefore = fixes.size
            advanceTimeBy(61 * SECOND)
            if (fixes.size > fixesBefore) {
                waits += fixTimes.last() - droppedAt
                fixWorks(watcher)
            }
        }
        return waits
    }

    private fun TestScope.watcher() = StuckWatcher(
        backgroundScope,
        { enabled },
        { fixRunning },
        { onCall },
        { currentTime + slept },
        { live },
        {
            fixes += it
            fixTimes += currentTime
            fixRunning = true
        },
        { keepsDroppingAlerts++ },
        { alertsCleared++ },
    )

    private fun TestScope.fixWorks(watcher: StuckWatcher, source: FixSource = fixes.last()) {
        watcher.onNetworkChanged(LTE)
        fixRunning = false
        watcher.onFixFinished(source, FixResult.BACK_ON_4G, currentTime + slept)
    }

    private fun TestScope.fixFails(watcher: StuckWatcher, source: FixSource = fixes.last()) {
        fixRunning = false
        watcher.onFixFinished(source, FixResult.STILL_ON_2G, currentTime + slept)
    }
}
