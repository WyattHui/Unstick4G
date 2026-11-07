package com.wyatthui.unstick4g

import android.app.Application
import android.content.Context
import com.wyatthui.unstick4g.data.AppDatabase
import com.wyatthui.unstick4g.data.ShizukuStop
import com.wyatthui.unstick4g.fix.FixRunner
import com.wyatthui.unstick4g.shizuku.ShizukuState
import com.wyatthui.unstick4g.shizuku.ShizukuStatus
import com.wyatthui.unstick4g.shizuku.shizukuStopped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass

class App : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database by lazy { AppDatabase.open(this) }
    val settings by lazy { Settings(this) }
    val fixRunner by lazy { FixRunner(this, database.logDao(), settings, appScope) }

    override fun onCreate() {
        super.onCreate()
        HiddenApiBypass.addHiddenApiExemptions("L")
        Notifications.createChannels(this)
        ShizukuState.start()
        appScope.launch {
            var previous: ShizukuStatus? = null
            ShizukuState.status.collect { status ->
                onShizukuStatus(previous, status)
                previous = status
            }
        }
    }

    private fun onShizukuStatus(previous: ShizukuStatus?, status: ShizukuStatus) {
        if (status != ShizukuStatus.NOT_RUNNING) Notifications.cancelShizukuOff(this)
        if (status == ShizukuStatus.READY) fixRunner.restorePendingFlip()
        if (shizukuStopped(previous, status)) {
            Notifications.showShizukuOff(this)
            val stop = ShizukuStop(timestamp = System.currentTimeMillis())
            appScope.launch { runCatching { database.logDao().insert(stop) } }
        }
    }
}

val Context.app: App
    get() = applicationContext as App
