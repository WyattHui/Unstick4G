package com.wyatthui.unstick4g.data

import android.telephony.TelephonyManager.NETWORK_TYPE_EDGE
import android.telephony.TelephonyManager.NETWORK_TYPE_GSM
import android.telephony.TelephonyManager.NETWORK_TYPE_LTE
import com.wyatthui.unstick4g.logger.LteCell
import com.wyatthui.unstick4g.logger.formatLteCells
import org.junit.Assert.assertEquals
import org.junit.Test

private const val AT_2026_10_25_14_03_12_UTC = 1_792_936_992_000L

class CsvExportTest {
    @Test
    fun `network events use UTC timestamps and readable network types`() {
        val csv = networkEventsCsv(
            listOf(
                NetworkEvent(
                    timestamp = AT_2026_10_25_14_03_12_UTC,
                    dataType = NETWORK_TYPE_LTE,
                    voiceType = NETWORK_TYPE_GSM,
                    signalDbm = -98,
                    inDrive = true,
                ),
            ),
        )

        assertEquals(
            "timestamp,data_type,voice_type,signal_dbm,in_drive\n" +
                "2026-10-25T14:03:12Z,LTE,GSM,-98,true\n",
            csv,
        )
    }

    @Test
    fun `missing values are empty fields, not the word null`() {
        val csv = drivesCsv(listOf(Drive(id = 4, startedAt = AT_2026_10_25_14_03_12_UTC, endedAt = null)))

        assertEquals("drive_id,start,end\n4,2026-10-25T14:03:12Z,\n", csv)
    }

    @Test
    fun `location samples join to drives and keep LTE cells in one field`() {
        val cells = formatLteCells(
            listOf(
                LteCell(ci = 123456, pci = 45, earfcn = 6300, bands = listOf(20), rsrp = -110),
                LteCell(ci = null, pci = 300, earfcn = 1300, bands = listOf(3), rsrp = -95),
            ),
        )
        val csv = locationSamplesCsv(
            listOf(
                LocationSample(
                    timestamp = AT_2026_10_25_14_03_12_UTC,
                    lat = 52.8,
                    lon = -1.63,
                    accuracy = 5.0f,
                    speed = 22.5f,
                    driveId = 4,
                    dataType = NETWORK_TYPE_EDGE,
                    voiceType = NETWORK_TYPE_EDGE,
                    lteCells = cells,
                ),
            ),
        )

        assertEquals(
            "timestamp,lat,lon,accuracy_m,speed_mps,drive_id,data_type,voice_type,lte_cells\n" +
                "2026-10-25T14:03:12Z,52.8,-1.63,5.0,22.5,4,EDGE,EDGE,123456:45:6300:20:-110;:300:1300:3:-95\n",
            csv,
        )
    }

    @Test
    fun `fix attempts export even with no rows, so every file has a header`() {
        assertEquals(
            "timestamp,method,type_before,type_after,seconds_on_2g_before,result,duration_ms\n",
            fixAttemptsCsv(emptyList()),
        )
    }

    @Test
    fun `Shizuku stops export their UTC time, to show when Shizuku died`() {
        val csv = shizukuStopsCsv(listOf(ShizukuStop(timestamp = AT_2026_10_25_14_03_12_UTC)))

        assertEquals("timestamp\n2026-10-25T14:03:12Z\n", csv)
    }

    @Test
    fun `fields with commas, quotes or newlines are quoted so Python reads them as one value`() {
        val csv = csv(listOf("a", "b", "c"), listOf(listOf("x,y", "say \"hi\"", "two\nlines")))

        assertEquals("a,b,c\n\"x,y\",\"say \"\"hi\"\"\",\"two\nlines\"\n", csv)
    }
}
