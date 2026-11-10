package com.wyatthui.unstick4g.fix

import android.telephony.TelephonyManager.NETWORK_TYPE_EDGE
import android.telephony.TelephonyManager.NETWORK_TYPE_GSM
import android.telephony.TelephonyManager.NETWORK_TYPE_LTE
import android.telephony.TelephonyManager.NETWORK_TYPE_UNKNOWN
import com.wyatthui.unstick4g.NetworkSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val ON_2G = NetworkSnapshot(NETWORK_TYPE_EDGE, NETWORK_TYPE_GSM)
private val ON_4G = NetworkSnapshot(NETWORK_TYPE_LTE, NETWORK_TYPE_LTE)
private val NO_SIGNAL = NetworkSnapshot(NETWORK_TYPE_UNKNOWN, NETWORK_TYPE_UNKNOWN)
private val LTE_DATA_GSM_VOICE = NetworkSnapshot(NETWORK_TYPE_LTE, NETWORK_TYPE_GSM)

@OptIn(ExperimentalCoroutinesApi::class)
class AutoFixerTest {
    private var network: NetworkSnapshot? = ON_4G
    private var enabled = true
    private var blocked = false
    private var fixes = 0
    private var awakeFor = 0L
    private var parkedCalls = 0

    @Test
    fun `parking on 2G fixes once, 10 seconds later`() = runTest {
        network = ON_2G

        autoFixer().parked()
        advanceTimeBy(9_000)
        assertEquals(0, fixes)

        advanceTimeBy(2_000)
        assertEquals(1, fixes)

        network = ON_4G
        advanceUntilIdle()
        assertEquals(1, fixes)
    }

    @Test
    fun `parking keeps the phone awake for the whole minute`() = runTest {
        autoFixer().parked()

        // With the screen off the CPU sleeps, and the minute would stop counting, so the Fix could come hours late.
        assertTrue(awakeFor > 60_000)
    }

    @Test
    fun `parking during a call skips those checks, and a check after the call still fixes`() = runTest {
        network = ON_2G
        blocked = true

        autoFixer().parked()
        advanceTimeBy(25_000)

        // A refused Fix during a call would only alert you mid-call.
        assertEquals(0, fixes)

        blocked = false
        advanceTimeBy(6_000)
        assertEquals(1, fixes)
    }

    @Test
    fun `after the parking minute it tells the stuck rule you have parked`() = runTest {
        autoFixer().parked()
        advanceTimeBy(61_000)

        // A new place may have 4G, so the stuck tries should start again quickly instead of waiting up to an hour.
        assertEquals(1, parkedCalls)
    }

    @Test
    fun `a parking Fix at 10 seconds also tells the stuck rule you have parked`() = runTest {
        network = ON_2G

        autoFixer().parked()
        advanceTimeBy(11_000)

        assertEquals(1, fixes)
        assertEquals(1, parkedCalls)
    }

    @Test
    fun `a reconnect within the minute is not a parking`() = runTest {
        val autoFixer = autoFixer()

        autoFixer.parked()
        advanceTimeBy(2_000)
        autoFixer.onCarConnectionChanged(connected = true)
        advanceUntilIdle()

        assertEquals(0, parkedCalls)
    }

    @Test
    fun `a short Bluetooth drop mid-drive is not treated as parking`() = runTest {
        network = ON_2G
        val autoFixer = autoFixer()

        autoFixer.parked()
        advanceTimeBy(2_000)
        autoFixer.onCarConnectionChanged(connected = true)
        advanceUntilIdle()

        // Flipping the network halfway through a drive would cut navigation and calls for nothing.
        assertEquals(0, fixes)
    }

    @Test
    fun `parking on 4G and dropping to 2G within the minute is fixed at the next check`() = runTest {
        autoFixer().parked()
        advanceTimeBy(40_000)
        network = ON_2G
        advanceTimeBy(11_000)

        assertEquals(1, fixes)
    }

    @Test
    fun `after a minute it stops checking`() = runTest {
        autoFixer().parked()
        advanceTimeBy(61_000)
        network = ON_2G
        advanceUntilIdle()

        assertEquals(0, fixes)
    }

    @Test
    fun `a no-signal moment at parking is fixed at the first check after it settles on 2G`() = runTest {
        network = NO_SIGNAL

        autoFixer().parked()
        advanceTimeBy(25_000)
        network = ON_2G
        advanceTimeBy(6_000)

        // The logs show no-signal moments of up to 45 s before 2G, so one early check alone would miss them.
        assertEquals(1, fixes)
    }

    @Test
    fun `a no-signal moment that ends on 4G is left alone`() = runTest {
        network = NO_SIGNAL

        autoFixer().parked()
        advanceTimeBy(1_000)
        network = ON_4G
        advanceUntilIdle()

        assertEquals(0, fixes)
    }

    @Test
    fun `parking on 4G never touches the network`() = runTest {
        autoFixer().parked()
        advanceUntilIdle()

        assertEquals(0, fixes)
    }

    @Test
    fun `parking with 4G data and GSM voice never touches the network`() = runTest {
        network = LTE_DATA_GSM_VOICE

        autoFixer().parked()
        advanceUntilIdle()

        // Data is what you notice; flipping a phone that already has 4G data would cut it for nothing.
        assertEquals(0, fixes)
    }

    @Test
    fun `with the switch off, parking on 2G does nothing`() = runTest {
        enabled = false
        network = ON_2G

        autoFixer().parked()
        advanceUntilIdle()

        assertEquals(0, fixes)
    }

    @Test
    fun `turning the switch off during the minute stops the Fix`() = runTest {
        network = NO_SIGNAL

        autoFixer().parked()
        advanceTimeBy(5_000)
        enabled = false
        network = ON_2G
        advanceUntilIdle()

        assertEquals(0, fixes)
    }

    @Test
    fun `a second disconnect replaces the first, so the phone is fixed only once`() = runTest {
        network = ON_2G
        val autoFixer = autoFixer()

        autoFixer.parked()
        advanceTimeBy(5_000)
        autoFixer.parked()
        advanceTimeBy(11_000)

        assertEquals(1, fixes)
    }

    private fun TestScope.autoFixer() = AutoFixer(this, { enabled }, { blocked }, { network }, { awakeFor = it }, { parkedCalls++ }) { fixes++ }

    private fun AutoFixer.parked() = onCarConnectionChanged(connected = false)
}
