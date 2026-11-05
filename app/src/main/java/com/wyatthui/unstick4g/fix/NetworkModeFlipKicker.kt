package com.wyatthui.unstick4g.fix

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface PreferredNetworkModes {
    fun read(subId: Int): Int

    fun write(subId: Int, mode: Int)
}

data class FlipRecord(val subId: Int, val originalMode: Int)

interface FlipRecordStore {
    fun save(record: FlipRecord)

    fun load(): FlipRecord?

    fun clear()
}

class NetworkModeFlipKicker(
    private val modes: PreferredNetworkModes,
    private val records: FlipRecordStore,
    private val subId: () -> Int,
) : NetworkKicker {
    override val method = FixMethod.FLIP
    private val lock = Mutex()

    override fun isAvailable() = runCatching { modes.read(subId()) }.isSuccess

    fun originalMode() = records.load()?.originalMode ?: modes.read(subId())

    override suspend fun kick() = lock.withLock {
        restoreRecord()
        val id = subId()
        records.save(FlipRecord(id, modes.read(id)))
        try {
            modes.write(id, NetworkModes.GSM_ONLY)
            delay(HOLD_MS)
        } finally {
            restoreRecord()
        }
    }

    suspend fun restorePending() = lock.withLock { restoreRecord() }

    private fun restoreRecord() {
        val record = records.load() ?: return
        if (modes.read(record.subId) != record.originalMode) modes.write(record.subId, record.originalMode)
        records.clear()
    }

    private companion object {
        const val HOLD_MS = 4_000L
    }
}

object NetworkModes {
    const val GSM_ONLY = 1

    private val AOSP_NAMES = listOf(
        "WCDMA_PREF",
        "GSM_ONLY",
        "WCDMA_ONLY",
        "GSM_UMTS",
        "CDMA",
        "CDMA_NO_EVDO",
        "EVDO_NO_CDMA",
        "GLOBAL",
        "LTE_CDMA_EVDO",
        "LTE_GSM_WCDMA",
        "LTE_CDMA_EVDO_GSM_WCDMA",
        "LTE_ONLY",
        "LTE_WCDMA",
        "TDSCDMA_ONLY",
        "TDSCDMA_WCDMA",
        "LTE_TDSCDMA",
        "TDSCDMA_GSM",
        "LTE_TDSCDMA_GSM",
        "TDSCDMA_GSM_WCDMA",
        "LTE_TDSCDMA_WCDMA",
        "LTE_TDSCDMA_GSM_WCDMA",
        "TDSCDMA_CDMA_EVDO_GSM_WCDMA",
        "LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA",
        "NR_ONLY",
        "NR_LTE",
        "NR_LTE_CDMA_EVDO",
        "NR_LTE_GSM_WCDMA",
        "NR_LTE_CDMA_EVDO_GSM_WCDMA",
        "NR_LTE_WCDMA",
        "NR_LTE_TDSCDMA",
        "NR_LTE_TDSCDMA_GSM",
        "NR_LTE_TDSCDMA_WCDMA",
        "NR_LTE_TDSCDMA_GSM_WCDMA",
        "NR_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA",
    )

    fun isAuto(mode: Int) = name(mode).split('_').containsAll(listOf("LTE", "GSM"))

    fun name(mode: Int) = AOSP_NAMES.getOrNull(mode) ?: "MODE_$mode"
}

class PrefsFlipRecordStore(context: Context) : FlipRecordStore {
    private val prefs = context.getSharedPreferences("flip_record", Context.MODE_PRIVATE)

    override fun save(record: FlipRecord) {
        val saved = prefs.edit()
            .putInt(KEY_SUB_ID, record.subId)
            .putInt(KEY_ORIGINAL_MODE, record.originalMode)
            .commit()
        check(saved) { "Couldn't save the original network mode, so the flip was not started" }
    }

    override fun load(): FlipRecord? {
        if (!prefs.contains(KEY_ORIGINAL_MODE)) return null
        return FlipRecord(prefs.getInt(KEY_SUB_ID, -1), prefs.getInt(KEY_ORIGINAL_MODE, -1))
    }

    override fun clear() {
        prefs.edit().clear().commit()
    }

    private companion object {
        const val KEY_SUB_ID = "sub_id"
        const val KEY_ORIGINAL_MODE = "original_mode"
    }
}
