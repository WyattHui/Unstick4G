package com.wyatthui.unstick4g.fix

import android.annotation.SuppressLint
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.wyatthui.unstick4g.MainActivity
import com.wyatthui.unstick4g.app
import com.wyatthui.unstick4g.shizuku.ShizukuState
import com.wyatthui.unstick4g.shizuku.ShizukuStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class FixTileService : TileService() {
    private val scope = MainScope()
    private var listening: Job? = null

    override fun onStartListening() {
        listening = scope.launch {
            combine(ShizukuState.status, app.fixRunner.state, ::Pair).collect { (status, fixState) ->
                render(status, fixState)
            }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
    }

    override fun onClick() {
        when (ShizukuState.status.value) {
            ShizukuStatus.NOT_RUNNING -> openAndCollapse(ShizukuState.openIntent(this) ?: mainActivityIntent())
            ShizukuStatus.NO_PERMISSION -> openAndCollapse(mainActivityIntent())
            ShizukuStatus.READY -> app.fixRunner.start()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render(status: ShizukuStatus, fixState: FixState) {
        val tile = qsTile ?: return
        val (state, subtitle) = look(status, fixState)
        tile.state = state
        tile.subtitle = subtitle
        tile.updateTile()
    }

    private fun look(status: ShizukuStatus, fixState: FixState): Pair<Int, String> = when {
        status == ShizukuStatus.NOT_RUNNING -> Tile.STATE_INACTIVE to "Needs Shizuku"
        status == ShizukuStatus.NO_PERMISSION -> Tile.STATE_INACTIVE to "Needs permission"
        fixState is FixState.Working -> Tile.STATE_ACTIVE to "Working…"
        fixState is FixState.Done && System.currentTimeMillis() - fixState.at < RESULT_SHOWN_MS ->
            Tile.STATE_ACTIVE to fixState.outcome.result.shortLabel
        else -> Tile.STATE_ACTIVE to "Ready"
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun openAndCollapse(intent: Intent) {
        startActivityAndCollapse(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun mainActivityIntent() = Intent(this, MainActivity::class.java)

    private companion object {
        const val RESULT_SHOWN_MS = 60_000L
    }
}
