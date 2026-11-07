package com.wyatthui.unstick4g.shizuku

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuStoppedTest {
    @Test
    fun `Shizuku going away after it was running counts as a stop`() {
        assertTrue(shizukuStopped(ShizukuStatus.READY, ShizukuStatus.NOT_RUNNING))
        assertTrue(shizukuStopped(ShizukuStatus.NO_PERMISSION, ShizukuStatus.NOT_RUNNING))
    }

    @Test
    fun `the first status after the app starts is never a stop`() {
        // Every app start reports NOT_RUNNING until Shizuku's binder arrives, which must not post an alert.
        assertFalse(shizukuStopped(null, ShizukuStatus.NOT_RUNNING))
    }

    @Test
    fun `losing only the permission is not a stop, because Shizuku is still running`() {
        assertFalse(shizukuStopped(ShizukuStatus.READY, ShizukuStatus.NO_PERMISSION))
    }
}
