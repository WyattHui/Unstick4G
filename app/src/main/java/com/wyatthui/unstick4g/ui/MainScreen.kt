package com.wyatthui.unstick4g.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wyatthui.unstick4g.FixMethodSetting
import com.wyatthui.unstick4g.NetworkSnapshot
import com.wyatthui.unstick4g.Settings
import com.wyatthui.unstick4g.app
import com.wyatthui.unstick4g.data.FixAttempt
import com.wyatthui.unstick4g.data.LogDao
import com.wyatthui.unstick4g.data.NetworkEvent
import com.wyatthui.unstick4g.data.exportZip
import com.wyatthui.unstick4g.data.shareIntent
import com.wyatthui.unstick4g.fix.FixResult
import com.wyatthui.unstick4g.fix.FixSource
import com.wyatthui.unstick4g.fix.FixState
import com.wyatthui.unstick4g.logger.DrivingState
import com.wyatthui.unstick4g.logger.LoggerService
import com.wyatthui.unstick4g.logger.LoggerStatus
import com.wyatthui.unstick4g.logger.pairedDevices
import com.wyatthui.unstick4g.shizuku.ShizukuState
import com.wyatthui.unstick4g.shizuku.ShizukuStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val RECENT_LIMIT = 50

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM HH:mm:ss").withZone(ZoneId.systemDefault())

private val FOREGROUND_PERMISSIONS = arrayOf(
    Manifest.permission.READ_PHONE_STATE,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

private data class Permissions(val phone: Boolean, val location: Boolean, val backgroundLocation: Boolean)

private data class LogLine(val timestamp: Long, val text: String)

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val app = context.app
    val dao = remember { app.database.logDao() }
    val shizuku by ShizukuState.status.collectAsStateWithLifecycle()
    val logger by LoggerService.status.collectAsStateWithLifecycle()
    val fixState by app.fixRunner.state.collectAsStateWithLifecycle()
    val events by remember { dao.recentEvents(RECENT_LIMIT) }.collectAsStateWithLifecycle(emptyList())
    val attempts by remember { dao.recentFixAttempts(RECENT_LIMIT) }.collectAsStateWithLifecycle(emptyList())
    var permissions by remember { mutableStateOf(readPermissions(context)) }
    val onPermissionsChanged = {
        permissions = readPermissions(context)
        LoggerService.start(context)
    }
    LifecycleResumeEffect(Unit) {
        onPermissionsChanged()
        onPauseOrDispose {}
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 16.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { StatusSection(shizuku, logger, permissions, onPermissionsChanged) }
            item { FixSection(shizuku, fixState, attempts.firstOrNull(), app.fixRunner::start) }
            item { SettingsSection(app.settings) }
            item { ExportSection(dao) }
            item { Text("Recent log", style = MaterialTheme.typography.titleMedium) }
            items(logLines(events, attempts)) { LogRow(it) }
        }
    }
}

private fun readPermissions(context: Context): Permissions {
    fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    return Permissions(
        phone = granted(Manifest.permission.READ_PHONE_STATE),
        location = granted(Manifest.permission.ACCESS_FINE_LOCATION),
        backgroundLocation = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
    )
}

private fun logLines(events: List<NetworkEvent>, attempts: List<FixAttempt>): List<LogLine> {
    val eventLines = events.map { LogLine(it.timestamp, eventText(it)) }
    val attemptLines = attempts.map { LogLine(it.timestamp, attemptText(it)) }
    return (eventLines + attemptLines).sortedByDescending { it.timestamp }
}

private fun eventText(event: NetworkEvent) = buildString {
    append(NetworkSnapshot(event.dataType, event.voiceType).label)
    event.signalDbm?.let { append(" · $it dBm") }
    if (event.inDrive) append(" · in car")
}

private fun attemptText(attempt: FixAttempt) = buildString {
    val how = attempt.method.lowercase() + if (attempt.source == FixSource.CAR_DISCONNECT.name) ", auto" else ""
    append("Fix ($how): ${FixResult.valueOf(attempt.result).shortLabel}")
    attempt.secondsOn2gBefore?.let { append(" · ${it}s on 2G before") }
}

@Composable
private fun StatusSection(
    shizuku: ShizukuStatus,
    logger: LoggerStatus,
    permissions: Permissions,
    onPermissionsChanged: () -> Unit,
) {
    val context = LocalContext.current
    val foregroundRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { onPermissionsChanged() }
    val backgroundRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { onPermissionsChanged() }

    Section("Status") {
        StatusRow("Shizuku", shizukuText(shizuku)) {
            when (shizuku) {
                ShizukuStatus.NOT_RUNNING -> TextButton(
                    onClick = { ShizukuState.openIntent(context)?.let(context::startActivity) },
                ) { Text("Open") }
                ShizukuStatus.NO_PERMISSION -> TextButton(onClick = ShizukuState::requestPermission) { Text("Allow") }
                ShizukuStatus.READY -> Unit
            }
        }
        StatusRow("Logger", if (logger.running) "On" else "Off")
        StatusRow("Network", logger.network?.label ?: "—")
        StatusRow("Driving", drivingText(logger.driving))
        StatusRow("Phone permission", if (permissions.phone) "Granted" else "Missing") {
            if (!permissions.phone) {
                TextButton(onClick = { foregroundRequest.launch(FOREGROUND_PERMISSIONS) }) { Text("Grant") }
            }
        }
        StatusRow("Location permission", locationText(permissions)) {
            when {
                !permissions.location -> TextButton(
                    onClick = { foregroundRequest.launch(FOREGROUND_PERMISSIONS) },
                ) { Text("Grant") }
                !permissions.backgroundLocation -> TextButton(
                    onClick = { backgroundRequest.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
                ) { Text("Allow all the time") }
            }
        }
    }
}

private fun shizukuText(status: ShizukuStatus) = when (status) {
    ShizukuStatus.NOT_RUNNING -> "Off"
    ShizukuStatus.NO_PERMISSION -> "Running, needs permission"
    ShizukuStatus.READY -> "Ready"
}

private fun drivingText(driving: DrivingState) = when {
    driving.isDriving -> "Yes"
    driving.inCar -> "In car, location off"
    else -> "No"
}

private fun locationText(permissions: Permissions) = when {
    permissions.backgroundLocation -> "All the time"
    permissions.location -> "Only while using the app"
    else -> "Missing"
}

@Composable
private fun StatusRow(label: String, value: String, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
        action()
    }
}

@Composable
private fun FixSection(shizuku: ShizukuStatus, fixState: FixState, lastAttempt: FixAttempt?, onFix: () -> Unit) {
    val working = fixState == FixState.Working
    Section("Fix") {
        Button(
            onClick = onFix,
            enabled = shizuku == ShizukuStatus.READY && !working,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (working) "Working…" else "Fix 4G") }
        lastResultText(fixState, lastAttempt)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

private fun lastResultText(fixState: FixState, lastAttempt: FixAttempt?): String? = when {
    fixState is FixState.Done -> fixState.outcome.message
    lastAttempt != null -> "Last: ${FixResult.valueOf(lastAttempt.result).shortLabel} at ${time(lastAttempt.timestamp)}"
    else -> null
}

@Composable
private fun SettingsSection(settings: Settings) {
    val context = LocalContext.current
    val paired = remember { pairedDevices(context) }
    var carAddress by remember { mutableStateOf(settings.carAddress) }
    var fixMethod by remember { mutableStateOf(settings.fixMethod) }
    var autoFix by remember { mutableStateOf(settings.autoFix) }

    Section("Settings") {
        Text("Car Bluetooth", style = MaterialTheme.typography.titleSmall)
        if (paired.isEmpty()) Text("No paired Bluetooth devices. Is Bluetooth on?")
        paired.forEach { device ->
            ChoiceRow(device.name, selected = device.address == carAddress) {
                settings.carAddress = device.address
                carAddress = device.address
                LoggerService.start(context)
            }
        }
        Text("Fix method", style = MaterialTheme.typography.titleSmall)
        FixMethodSetting.entries.forEach { method ->
            ChoiceRow(method.label, selected = method == fixMethod) {
                settings.fixMethod = method
                fixMethod = method
            }
        }
        SwitchRow("Auto-fix when the car disconnects", checked = autoFix) {
            settings.autoFix = it
            autoFix = it
        }
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onChange, role = Role.Switch)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ExportSection(dao: LogDao) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                exporting = true
                error = null
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { exportZip(context, dao) } }
                        .onSuccess { context.startActivity(shareIntent(context, it)) }
                        .onFailure { error = it.message }
                    exporting = false
                }
            },
            enabled = !exporting,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (exporting) "Exporting…" else "Export CSV") }
        error?.let { Text("Export failed: $it", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun LogRow(line: LogLine) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            time(line.timestamp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(line.text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

private fun time(epochMillis: Long): String = TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis))
