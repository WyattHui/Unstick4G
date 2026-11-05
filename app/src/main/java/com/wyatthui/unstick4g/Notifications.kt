package com.wyatthui.unstick4g

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.wyatthui.unstick4g.shizuku.ShizukuState

object Notifications {
    const val LOGGER_ID = 1
    private const val SHIZUKU_OFF_ID = 2
    private const val FIX_RESULT_ID = 3
    private const val RESTORE_FAILED_ID = 4
    private const val LOGGER_CHANNEL = "logger"
    private const val ALERTS_CHANNEL = "alerts"

    fun createChannels(context: Context) {
        manager(context).createNotificationChannels(
            listOf(
                NotificationChannel(LOGGER_CHANNEL, "Background logger", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(ALERTS_CHANNEL, "Fix results and alerts", NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    fun logger(context: Context, text: String): Notification =
        Notification.Builder(context, LOGGER_CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(text)
            .setContentIntent(pendingActivity(context, LOGGER_ID, mainActivity(context)))
            .setOngoing(true)
            .build()

    fun showShizukuOff(context: Context) {
        val openShizuku = ShizukuState.openIntent(context) ?: mainActivity(context)
        alert(context, SHIZUKU_OFF_ID, "Shizuku is off — tap to start it", null, openShizuku)
    }

    fun cancelShizukuOff(context: Context) {
        manager(context).cancel(SHIZUKU_OFF_ID)
    }

    fun showFixResult(context: Context, message: String) {
        alert(context, FIX_RESULT_ID, message, null, mainActivity(context))
    }

    fun showRestoreFailed(context: Context, error: String) {
        alert(
            context,
            RESTORE_FAILED_ID,
            "Couldn't restore the network mode",
            "Set Network mode back to auto in Settings. ($error)",
            mainActivity(context),
        )
    }

    private fun alert(context: Context, id: Int, title: String, text: String?, tap: Intent) {
        val notification = Notification.Builder(context, ALERTS_CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingActivity(context, id, tap))
            .setAutoCancel(true)
            .build()
        manager(context).notify(id, notification)
    }

    private fun mainActivity(context: Context) = Intent(context, MainActivity::class.java)

    private fun pendingActivity(context: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)
}
