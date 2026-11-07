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

@OptIn(ExperimentalCoroutinesApi::class)
class AutoFixerTest {
    private var network: NetworkSnapshot? = ON_4G
    private var enabled = true
    private var fixes = 0
    private var awakeFor = 0L

    @Test
    fun `parking on 2G fixes once, a minute later`() = runTest {
        network = ON_2G

        autoFixer().parked()
        advanceTimeBy(59_000)
        assertEquals(0, fixes)

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
    fun `parking on 4G and dropping to 2G within the minute is fixed`() = runTest {
        autoFixer().parked()
        advanceTimeBy(40_000)
        network = ON_2G
        advanceUntilIdle()

        assertEquals(1, fixes)
    }

    @Test
    fun `a no-signal moment at parking that settles on 2G is fixed`() = runTest {
        network = NO_SIGNAL

        autoFixer().parked()
        advanceTimeBy(1_000)
        network = ON_2G
        advanceUntilIdle()

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
        network = ON_2G

        autoFixer().parked()
        advanceTimeBy(30_000)
        enabled = false
        advanceUntilIdle()

        assertEquals(0, fixes)
    }

    @Test
    fun `a second disconnect replaces the first, so the phone is fixed only once`() = runTest {
        network = ON_2G
        val autoFixer = autoFixer()

        autoFixer.parked()
        advanceTimeBy(30_000)
        autoFixer.parked()
        advanceUntilIdle()

        assertEquals(1, fixes)
    }

    private fun TestScope.autoFixer() = AutoFixer(this, { enabled }, { network }, { awakeFor = it }) { fixes++ }

    private fun AutoFixer.parked() = onCarConnectionChanged(connected = false)
}
