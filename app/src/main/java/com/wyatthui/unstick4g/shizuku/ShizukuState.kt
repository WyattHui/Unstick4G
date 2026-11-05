package com.wyatthui.unstick4g.shizuku

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku

enum class ShizukuStatus { NOT_RUNNING, NO_PERMISSION, READY }

object ShizukuState {
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val PERMISSION_REQUEST_CODE = 1

    private val mutableStatus = MutableStateFlow(ShizukuStatus.NOT_RUNNING)
    val status: StateFlow<ShizukuStatus> = mutableStatus

    fun start() {
        Shizuku.addBinderReceivedListenerSticky(::refresh)
        Shizuku.addBinderDeadListener(::refresh)
        Shizuku.addRequestPermissionResultListener { _, _ -> refresh() }
    }

    fun requestPermission() = Shizuku.requestPermission(PERMISSION_REQUEST_CODE)

    fun openIntent(context: Context): Intent? = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)

    private fun refresh() {
        mutableStatus.value = currentStatus()
    }

    private fun currentStatus() = when {
        !Shizuku.pingBinder() -> ShizukuStatus.NOT_RUNNING
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuStatus.NO_PERMISSION
        else -> ShizukuStatus.READY
    }
}
