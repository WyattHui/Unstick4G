package com.wyatthui.unstick4g.logger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DrivingStateTest {
    @Test
    fun `location switched on for walking navigation never records location`() {
        val walking = DrivingState(driveId = null, locationEnabled = true)

        assertNull(walking.sampleDriveId)
        assertFalse(walking.isDriving)
    }

    @Test
    fun `in the car with location off records no location`() {
        val parkedWithLocationOff = DrivingState(driveId = 7, locationEnabled = false)

        assertNull(parkedWithLocationOff.sampleDriveId)
        assertFalse(parkedWithLocationOff.isDriving)
    }

    @Test
    fun `in the car with location on, samples belong to the current drive`() {
        assertEquals(7L, DrivingState(driveId = 7, locationEnabled = true).sampleDriveId)
    }
}
