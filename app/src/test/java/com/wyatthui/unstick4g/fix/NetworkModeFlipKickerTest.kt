package com.wyatthui.unstick4g.fix

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SUB_ID = 3
private const val FIRMWARE_AUTO = 22

class NetworkModeFlipKickerTest {
    private val records = FakeRecords()

    @Test
    fun `flip ends on the exact mode the phone had, not a hard-coded auto value`() = runTest {
        val modes = FakeModes(FIRMWARE_AUTO)

        kicker(modes).kick()

        assertEquals(listOf(NetworkModes.GSM_ONLY, FIRMWARE_AUTO), modes.writes)
        assertEquals(FIRMWARE_AUTO, modes.current)
        assertNull(records.saved)
    }

    @Test
    fun `original mode is on disk before the phone is switched to 2G only`() = runTest {
        val modes = FakeModes(FIRMWARE_AUTO)
        var savedWhenSwitchingTo2g: FlipRecord? = null
        modes.beforeWrite = { mode -> if (mode == NetworkModes.GSM_ONLY) savedWhenSwitchingTo2g = records.saved }

        kicker(modes).kick()

        // A crash straight after the switch must still leave the mode to restore.
        assertEquals(FlipRecord(SUB_ID, FIRMWARE_AUTO), savedWhenSwitchingTo2g)
    }

    @Test
    fun `next start after a crash mid-flip restores the saved mode`() = runTest {
        val modes = FakeModes(NetworkModes.GSM_ONLY)
        records.saved = FlipRecord(SUB_ID, FIRMWARE_AUTO)

        kicker(modes).restorePending()

        assertEquals(FIRMWARE_AUTO, modes.current)
        assertNull(records.saved)
    }

    @Test
    fun `failed restore keeps the record so the next start tries again`() = runTest {
        val modes = FakeModes(NetworkModes.GSM_ONLY, failingWrites = setOf(FIRMWARE_AUTO))
        records.saved = FlipRecord(SUB_ID, FIRMWARE_AUTO)

        val error = runCatching { kicker(modes).restorePending() }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(FlipRecord(SUB_ID, FIRMWARE_AUTO), records.saved)
    }

    @Test
    fun `stuck 2G only flip reports the saved mode so the next Fix isn't refused as not auto`() {
        val modes = FakeModes(NetworkModes.GSM_ONLY)
        records.saved = FlipRecord(SUB_ID, FIRMWARE_AUTO)

        assertEquals(FIRMWARE_AUTO, kicker(modes).originalMode())
    }

    @Test
    fun `phone that refuses every write fails the flip but is left with no record to restore`() = runTest {
        val modes = FakeModes(FIRMWARE_AUTO, failingWrites = setOf(NetworkModes.GSM_ONLY, FIRMWARE_AUTO))

        val error = runCatching { kicker(modes).kick() }.exceptionOrNull()

        // The mode never changed, so a leftover record would only raise a false "couldn't restore" alert on every start.
        assertTrue(error is IllegalStateException)
        assertEquals(FIRMWARE_AUTO, modes.current)
        assertNull(records.saved)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `flip cancelled while on 2G only still restores the original mode`() = runTest {
        val modes = FakeModes(FIRMWARE_AUTO)
        val flip = launch { kicker(modes).kick() }
        advanceTimeBy(1_000)
        assertEquals(NetworkModes.GSM_ONLY, modes.current)

        flip.cancelAndJoin()

        assertEquals(FIRMWARE_AUTO, modes.current)
        assertNull(records.saved)
    }

    @Test
    fun `leftover record is restored before a new flip so the original is never replaced by 2G only`() = runTest {
        val modes = FakeModes(NetworkModes.GSM_ONLY)
        records.saved = FlipRecord(SUB_ID, FIRMWARE_AUTO)

        kicker(modes).kick()

        assertEquals(FIRMWARE_AUTO, modes.current)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `restore from elsewhere waits for a running flip instead of cutting into it`() = runTest {
        val modes = FakeModes(FIRMWARE_AUTO)
        val kicker = kicker(modes)
        launch { kicker.kick() }
        advanceTimeBy(1_000)

        launch { kicker.restorePending() }
        advanceTimeBy(1_000)

        // Run alongside the flip, a restore can clear the record while 2G only is still set, leaving nothing to restore.
        assertEquals(NetworkModes.GSM_ONLY, modes.current)
        advanceUntilIdle()
        assertEquals(FIRMWARE_AUTO, modes.current)
        assertNull(records.saved)
    }

    private fun kicker(modes: FakeModes) = NetworkModeFlipKicker(modes, records) { SUB_ID }
}

private class FakeModes(var current: Int, private val failingWrites: Set<Int> = emptySet()) : PreferredNetworkModes {
    val writes = mutableListOf<Int>()
    var beforeWrite: (Int) -> Unit = {}

    override fun read(subId: Int) = current

    override fun write(subId: Int, mode: Int) {
        beforeWrite(mode)
        check(mode !in failingWrites) { "write refused" }
        writes += mode
        current = mode
    }
}

private class FakeRecords : FlipRecordStore {
    var saved: FlipRecord? = null

    override fun save(record: FlipRecord) {
        saved = record
    }

    override fun load() = saved

    override fun clear() {
        saved = null
    }
}
