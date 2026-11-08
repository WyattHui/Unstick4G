package com.wyatthui.unstick4g.fix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixNotificationTest {
    @Test
    fun `an automatic Fix that worked stays quiet`() {
        assertFalse(shouldNotify(FixSource.CAR_DISCONNECT, FixResult.BACK_ON_4G))
    }

    @Test
    fun `an automatic Fix that didn't work always notifies`() {
        listOf(FixResult.STILL_ON_2G, FixResult.REFUSED, FixResult.FAILED).forEach {
            assertTrue(it.name, shouldNotify(FixSource.CAR_DISCONNECT, it))
        }
    }

    @Test
    fun `a Fix you tapped always reports its result`() {
        FixResult.entries.forEach { assertTrue(it.name, shouldNotify(FixSource.MANUAL, it)) }
    }

    @Test
    fun `an automatic Fix's message says it was automatic, so an unexpected alert is explained`() {
        FixSource.entries.filter { it != FixSource.MANUAL }.forEach {
            assertEquals(it.name, "Auto-fix: Still on 2G", notificationText(it, "Still on 2G"))
        }
        assertEquals("Still on 2G", notificationText(FixSource.MANUAL, "Still on 2G"))
    }
}
