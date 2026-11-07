package com.wyatthui.unstick4g

import android.content.Context
import androidx.core.content.edit

enum class FixMethodSetting(val label: String) {
    AUTO("Auto: flip, else flight mode"),
    FLIP_ONLY("Network-mode flip only"),
    FLIGHT_MODE_ONLY("Flight mode only"),
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var carAddress: String?
        get() = prefs.getString(KEY_CAR_ADDRESS, null)
        set(value) = prefs.edit { putString(KEY_CAR_ADDRESS, value) }

    var fixMethod: FixMethodSetting
        get() = prefs.getString(KEY_FIX_METHOD, null)?.let(FixMethodSetting::valueOf) ?: FixMethodSetting.AUTO
        set(value) = prefs.edit { putString(KEY_FIX_METHOD, value.name) }

    var autoFix: Boolean
        get() = prefs.getBoolean(KEY_AUTO_FIX, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_FIX, value) }

    private companion object {
        const val KEY_CAR_ADDRESS = "car_address"
        const val KEY_FIX_METHOD = "fix_method"
        const val KEY_AUTO_FIX = "auto_fix"
    }
}
