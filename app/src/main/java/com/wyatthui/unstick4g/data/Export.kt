package com.wyatthui.unstick4g.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.wyatthui.unstick4g.networkTypeName
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private val TIMESTAMP_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

private val FILE_DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

suspend fun exportZip(context: Context, dao: LogDao): File {
    val tables = mapOf(
        "network_events.csv" to networkEventsCsv(dao.allEvents()),
        "drives.csv" to drivesCsv(dao.allDrives()),
        "location_samples.csv" to locationSamplesCsv(dao.allSamples()),
        "fix_attempts.csv" to fixAttemptsCsv(dao.allFixAttempts()),
        "shizuku_stops.csv" to shizukuStopsCsv(dao.allShizukuStops()),
    )
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val file = File(dir, "unstick4g-${FILE_DATE_FORMAT.format(Instant.now())}.zip")
    ZipOutputStream(file.outputStream()).use { zip ->
        tables.forEach { (name, csv) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(csv.toByteArray())
            zip.closeEntry()
        }
    }
    return file
}

fun shareIntent(context: Context, file: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/zip")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return Intent.createChooser(send, "Export Unstick4G logs")
}

fun networkEventsCsv(events: List<NetworkEvent>) = csv(
    listOf("timestamp", "data_type", "voice_type", "signal_dbm", "in_drive"),
    events.map {
        listOf(
            timestamp(it.timestamp),
            networkTypeName(it.dataType),
            networkTypeName(it.voiceType),
            it.signalDbm,
            it.inDrive,
        )
    },
)

fun drivesCsv(drives: List<Drive>) = csv(
    listOf("drive_id", "start", "end"),
    drives.map { listOf(it.id, timestamp(it.startedAt), it.endedAt?.let(::timestamp)) },
)

fun locationSamplesCsv(samples: List<LocationSample>) = csv(
    listOf(
        "timestamp", "lat", "lon", "accuracy_m", "speed_mps", "drive_id",
        "data_type", "voice_type", "lte_cells",
    ),
    samples.map {
        listOf(
            timestamp(it.timestamp),
            it.lat,
            it.lon,
            it.accuracy,
            it.speed,
            it.driveId,
            networkTypeName(it.dataType),
            networkTypeName(it.voiceType),
            it.lteCells,
        )
    },
)

fun fixAttemptsCsv(attempts: List<FixAttempt>) = csv(
    listOf(
        "timestamp", "method", "type_before", "type_after",
        "seconds_on_2g_before", "result", "duration_ms",
    ),
    attempts.map {
        listOf(
            timestamp(it.timestamp),
            it.method,
            networkTypeName(it.typeBefore),
            networkTypeName(it.typeAfter),
            it.secondsOn2gBefore,
            it.result,
            it.durationMs,
        )
    },
)

fun shizukuStopsCsv(stops: List<ShizukuStop>) = csv(
    listOf("timestamp"),
    stops.map { listOf(timestamp(it.timestamp)) },
)

fun csv(header: List<String>, rows: List<List<Any?>>): String =
    (listOf(header) + rows).joinToString("") { row -> row.joinToString(",", transform = ::csvField) + "\n" }

private fun csvField(value: Any?): String {
    val text = value?.toString() ?: return ""
    val needsQuotes = text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
    return if (needsQuotes) "\"${text.replace("\"", "\"\"")}\"" else text
}

private fun timestamp(epochMillis: Long): String = TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(epochMillis))
