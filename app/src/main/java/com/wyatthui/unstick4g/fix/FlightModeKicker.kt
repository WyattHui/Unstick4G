package com.wyatthui.unstick4g.fix

import kotlinx.coroutines.delay

interface AirplaneMode {
    fun isSupported(): Boolean

    fun setEnabled(enabled: Boolean)
}

class FlightModeKicker(private val airplaneMode: AirplaneMode) : NetworkKicker {
    override val method = FixMethod.FLIGHT_MODE

    override fun isAvailable() = airplaneMode.isSupported()

    override suspend fun kick() {
        airplaneMode.setEnabled(true)
        try {
            delay(HOLD_MS)
        } finally {
            airplaneMode.setEnabled(false)
        }
    }

    private companion object {
        const val HOLD_MS = 3_000L
    }
}
