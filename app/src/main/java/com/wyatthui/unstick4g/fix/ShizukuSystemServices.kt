package com.wyatthui.unstick4g.fix

import android.content.Context
import android.os.IBinder
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.InvocationTargetException

class ShizukuSystemServices : PreferredNetworkModes, AirplaneMode {
    override fun read(subId: Int): Int {
        val mode = call(Context.TELEPHONY_SERVICE, TELEPHONY, "getPreferredNetworkType", subId) as Int
        check(mode >= 0) { "No preferred network mode for subscription $subId" }
        return mode
    }

    override fun write(subId: Int, mode: Int) {
        val accepted = call(Context.TELEPHONY_SERVICE, TELEPHONY, "setPreferredNetworkType", subId, mode) as Boolean
        check(accepted) { "The phone refused network mode $mode" }
    }

    override fun isSupported() = runCatching {
        Class.forName(CONNECTIVITY).getMethod("setAirplaneMode", Boolean::class.javaPrimitiveType)
    }.isSuccess

    override fun setEnabled(enabled: Boolean) {
        call(Context.CONNECTIVITY_SERVICE, CONNECTIVITY, "setAirplaneMode", enabled)
    }

    private fun call(service: String, interfaceName: String, method: String, vararg args: Any): Any? {
        val binder = requireNotNull(SystemServiceHelper.getSystemService(service)) { "No $service service" }
        val remote = Class.forName("$interfaceName\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, ShizukuBinderWrapper(binder))
        val types = args.map { it::class.javaPrimitiveType ?: it.javaClass }.toTypedArray()
        try {
            return Class.forName(interfaceName).getMethod(method, *types).invoke(remote, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private companion object {
        const val TELEPHONY = "com.android.internal.telephony.ITelephony"
        const val CONNECTIVITY = "android.net.IConnectivityManager"
    }
}
