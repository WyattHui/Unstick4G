package com.wyatthui.unstick4g.fix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkModesTest {
    @Test
    fun `auto modes that also include CDMA or TD-SCDMA still count as auto`() {
        listOf(9, 10, 20, 22, 26, 33).forEach { assertTrue("mode $it", NetworkModes.isAuto(it)) }
    }

    @Test
    fun `modes chosen on purpose in Settings are left alone`() {
        listOf(0, NetworkModes.GSM_ONLY, 2, 3, 11).forEach { assertFalse("mode $it", NetworkModes.isAuto(it)) }
    }

    @Test
    fun `names follow the AOSP numbering so refusal messages are right`() {
        assertEquals("WCDMA_PREF", NetworkModes.name(0))
        assertEquals("GSM_ONLY", NetworkModes.name(NetworkModes.GSM_ONLY))
        assertEquals("LTE_GSM_WCDMA", NetworkModes.name(9))
        assertEquals("LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA", NetworkModes.name(22))
        assertEquals("NR_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA", NetworkModes.name(33))
        assertEquals("MODE_99", NetworkModes.name(99))
    }
}
