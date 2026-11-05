package com.wyatthui.unstick4g.fix

import android.telephony.TelephonyManager.NETWORK_TYPE_EDGE
import android.telephony.TelephonyManager.NETWORK_TYPE_GPRS
import android.telephony.TelephonyManager.NETWORK_TYPE_GSM
import android.telephony.TelephonyManager.NETWORK_TYPE_LTE
import com.wyatthui.unstick4g.data.NetworkEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecondsOn2gTest {
    @Test
    fun `counts from the start of the current 2G run, not the latest 2G change`() {
        val events = listOf(event(0, NETWORK_TYPE_LTE), event(100, NETWORK_TYPE_EDGE), event(160, NETWORK_TYPE_GPRS))

        assertEquals(300L, secondsOn2gBefore(seconds(400), events))
    }

    @Test
    fun `voice on 2G counts as on 2G even when data is on 4G`() {
        val events = listOf(event(0, NETWORK_TYPE_LTE), event(50, NETWORK_TYPE_LTE, voice = NETWORK_TYPE_GSM))

        assertEquals(30L, secondsOn2gBefore(seconds(80), events))
    }

    @Test
    fun `zero when the phone is on 4G at the tap`() {
        val events = listOf(event(0, NETWORK_TYPE_EDGE), event(100, NETWORK_TYPE_LTE))

        assertEquals(0L, secondsOn2gBefore(seconds(400), events))
    }

    @Test
    fun `events after the tap and out of order are handled`() {
        val events = listOf(event(500, NETWORK_TYPE_LTE), event(100, NETWORK_TYPE_EDGE), event(0, NETWORK_TYPE_LTE))

        assertEquals(300L, secondsOn2gBefore(seconds(400), events))
    }

    @Test
    fun `unknown when nothing was logged before the tap`() {
        assertNull(secondsOn2gBefore(seconds(400), emptyList()))
    }

    private fun event(atSeconds: Long, data: Int, voice: Int = data) = NetworkEvent(
        timestamp = seconds(atSeconds),
        dataType = data,
        voiceType = voice,
        signalDbm = null,
        inDrive = false,
    )

    private fun seconds(value: Long) = value * 1_000
}
