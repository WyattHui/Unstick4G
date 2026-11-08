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
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val ON_2G = NetworkSnapshot(NETWORK_TYPE_EDGE, NETWORK_TYPE_GSM)
private val ON_4G = NetworkSnapshot(NETWORK_TYPE_LTE, NETWORK_TYPE_LTE)
private val NO_SIGNAL = NetworkSnapshot(NETWORK_TYPE_UNKNOWN, NETWORK_TYPE_UNKNOWN)
private const val MINUTE = 60_000L

@OptIn(ExperimentalCoroutinesApi::class)
class AutoFixerTest {
    private var network: NetworkSnapshot? = ON_4G
    private var enabled = true
    private var blocked = false
    private var fixes = 0
    private var awakeFor = 0L
    private var slept = 0L

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
    fun `while still on 2G it tries again 5, 15 and 45 minutes after parking`() = runTest {
        network = ON_2G

        autoFixer().parked()
        advanceTimeBy(11_000)
        assertEquals(1, fixes)
        advanceTimeBy(5 * MINUTE - 11_000 + 1_000)
        assertEquals(2, fixes)
        advanceTimeBy(10 * MINUTE)
        assertEquals(3, fixes)
        advanceTimeBy(30 * MINUTE)
        assertEquals(4, fixes)

        advanceUntilIdle()
        assertEquals(4, fixes)
    }

    @Test
    fun `turning the switch off after the first Fix stops the retries`() = runTest {
        network = ON_2G

        autoFixer().parked()
        advanceTimeBy(11_000)
        assertEquals(1, fixes)
        enabled = false
        advanceUntilIdle()

        assertEquals(1, fixes)
    }

    @Test
    fun `a retry that comes more than an hour after parking is skipped`() = runTest {
        network = ON_2G

        autoFixer().parked()
        advanceTimeBy(11_000)
        assertEquals(1, fixes)
        slept = 120 * MINUTE
        advanceUntilIdle()

        // While the phone sleeps the countdown stops, so a late retry could fire somewhere unrelated to the parking.
        assertEquals(1, fixes)
    }

    @Test
    fun `a drop to 2G a few minutes after parking is caught by the 5-minute check`() = runTest {
        autoFixer().parked()
        advanceTimeBy(3 * MINUTE)
        network = ON_2G
        advanceTimeBy(2 * MINUTE + 1_000)

        assertEquals(1, fixes)
    }

    @Test
    fun `parking keeps the phone awake for the whole minute`() = runTest {
        autoFixer().parked()

        // With the screen off the CPU sleeps, and the minute would stop counting, so the Fix could come hours late.
        assertTrue(awakeFor > 60_000)
    }

    @Test
    fun `parking during a call skips those checks, and a later check still fixes`() = runTest {
        network = ON_2G
        blocked = true

        autoFixer().parked()
        advanceTimeBy(61_000)

        // A refused Fix during a call would only alert you mid-call.
        assertEquals(0, fixes)

        blocked = false
        advanceTimeBy(4 * MINUTE)
        assertEquals(1, fixes)
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
    fun `after 45 minutes it stops checking`() = runTest {
        autoFixer().parked()
        advanceTimeBy(45 * MINUTE + 1_000)
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

    private fun TestScope.autoFixer() = AutoFixer(this, { enabled }, { blocked }, { network }, { awakeFor = it }, { currentTime + slept }) { fixes++ }

    private fun AutoFixer.parked() = onCarConnectionChanged(connected = false)
}
