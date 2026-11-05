package com.wyatthui.unstick4g.fix

enum class FixMethod { FLIP, FLIGHT_MODE }

interface NetworkKicker {
    val method: FixMethod

    fun isAvailable(): Boolean

    suspend fun kick()
}
