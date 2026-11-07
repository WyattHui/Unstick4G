package com.wyatthui.unstick4g.logger

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.os.PowerManager
import android.os.Looper
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.wyatthui.unstick4g.NetworkSnapshot
import com.wyatthui.unstick4g.Notifications
import com.wyatthui.unstick4g.app
import com.wyatthui.unstick4g.data.Drive
import com.wyatthui.unstick4g.data.LocationSample
import com.wyatthui.unstick4g.data.NetworkEvent
import com.wyatthui.unstick4g.fix.AutoFixer
import com.wyatthui.unstick4g.fix.FixSource
import com.wyatthui.unstick4g.shizuku.ShizukuState
import com.wyatthui.unstick4g.shizuku.ShizukuStatus
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

data class LoggerStatus(
    val running: Boolean = false,
    val network: NetworkSnapshot? = null,
    val driving: DrivingState = DrivingState(),
)

class LoggerService : Service() {
    private val scope = MainScope()
    private val driveLock = Mutex()
    private val dao by lazy { app.database.logDao() }
    private lateinit var telephony: TelephonyManager
    private lateinit var locationManager: LocationManager
    private lateinit var carTracker: CarTracker
    private var listeningToPhone = false
    private var gpsOn = false
    private var latestSignalDbm: Int? = null
    private var driving = DrivingState()
    private val settleWakeLock by lazy {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Unstick4G:settle")
            .apply { setReferenceCounted(false) }
    }
    private val autoFixer = AutoFixer(
        scope,
        { app.settings.autoFix },
        { mutableStatus.value.network },
        { settleWakeLock.acquire(it) },
    ) {
        app.fixRunner.start(FixSource.CAR_DISCONNECT)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private val phoneStateListener = object : PhoneStateListener() {
        override fun onServiceStateChanged(serviceState: ServiceState?) = recordNetworkIfChanged()

        override fun onDataConnectionStateChanged(state: Int, networkType: Int) = recordNetworkIfChanged()

        override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
            latestSignalDbm = signalStrength?.let(::servingDbm)
        }
    }

    private val locationListener = LocationListener(::recordLocationSample)

    private val locationModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateDriving(driving.copy(locationEnabled = locationManager.isLocationEnabled))
        }
    }

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(
            this,
            Notifications.LOGGER_ID,
            Notifications.logger(this, "Starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        telephony = getSystemService(TelephonyManager::class.java)
        locationManager = getSystemService(LocationManager::class.java)
        carTracker = CarTracker(this, app.settings, ::onCarConnectionChanged, autoFixer::onCarConnectionChanged)
        mutableStatus.update { it.copy(running = true) }
        updateDriving(driving.copy(locationEnabled = locationManager.isLocationEnabled))
        ContextCompat.registerReceiver(
            this,
            locationModeReceiver,
            IntentFilter(LocationManager.MODE_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scope.launch { driveLock.withLock { dao.endOpenDrives() } }
        carTracker.start()
        scope.launch { pollAndPurge() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        listenToPhoneIfPermitted()
        updateGps()
        carTracker.recheck()
        val fromBoot = intent?.getBooleanExtra(EXTRA_FROM_BOOT, false) == true
        if (fromBoot && ShizukuState.status.value == ShizukuStatus.NOT_RUNNING) {
            Notifications.showShizukuOff(this)
        }
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    override fun onDestroy() {
        telephony.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        locationManager.removeUpdates(locationListener)
        unregisterReceiver(locationModeReceiver)
        carTracker.stop()
        scope.cancel()
        mutableStatus.value = LoggerStatus()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun onCarConnectionChanged(connected: Boolean) {
        scope.launch {
            driveLock.withLock {
                if (connected == driving.inCar) return@withLock
                val now = System.currentTimeMillis()
                val driveId = if (connected) {
                    dao.insert(Drive(startedAt = now))
                } else {
                    driving.driveId?.let { dao.endDrive(it, now) }
                    null
                }
                updateDriving(driving.copy(driveId = driveId))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun listenToPhoneIfPermitted() {
        if (listeningToPhone || !hasPermission(Manifest.permission.READ_PHONE_STATE)) return
        telephony.listen(
            phoneStateListener,
            PhoneStateListener.LISTEN_SERVICE_STATE or
                PhoneStateListener.LISTEN_DATA_CONNECTION_STATE or
                PhoneStateListener.LISTEN_SIGNAL_STRENGTHS,
        )
        listeningToPhone = true
        recordNetworkIfChanged()
    }

    private suspend fun pollAndPurge() {
        var lastPurgeAt = 0L
        while (true) {
            recordNetworkIfChanged()
            val now = System.currentTimeMillis()
            if (now - lastPurgeAt >= PURGE_INTERVAL_MS) {
                dao.deleteOlderThan(now - RETENTION_MS)
                lastPurgeAt = now
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun updateDriving(state: DrivingState) {
        driving = state
        mutableStatus.update { it.copy(driving = state) }
        updateGps()
        refreshNotification()
    }

    @SuppressLint("MissingPermission")
    private fun recordNetworkIfChanged() {
        if (!hasPermission(Manifest.permission.READ_PHONE_STATE)) return
        val network = NetworkSnapshot(telephony.dataNetworkType, telephony.voiceNetworkType)
        if (network == mutableStatus.value.network) return
        mutableStatus.update { it.copy(network = network) }
        refreshNotification()
        val event = NetworkEvent(
            timestamp = System.currentTimeMillis(),
            dataType = network.dataType,
            voiceType = network.voiceType,
            signalDbm = latestSignalDbm,
            inDrive = driving.inCar,
        )
        scope.launch { dao.insert(event) }
        if (driving.isDriving) recordCurrentLocation()
    }

    @SuppressLint("MissingPermission")
    private fun recordCurrentLocation() {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return
        locationManager.getCurrentLocation(LocationManager.GPS_PROVIDER, null, mainExecutor) { location ->
            location?.let(::recordLocationSample)
        }
    }

    private fun recordLocationSample(location: Location) {
        val driveId = driving.sampleDriveId ?: return
        val network = mutableStatus.value.network
            ?: NetworkSnapshot(TelephonyManager.NETWORK_TYPE_UNKNOWN, TelephonyManager.NETWORK_TYPE_UNKNOWN)
        val sample = LocationSample(
            timestamp = System.currentTimeMillis(),
            lat = location.latitude,
            lon = location.longitude,
            accuracy = location.accuracy.takeIf { location.hasAccuracy() },
            speed = location.speed.takeIf { location.hasSpeed() },
            driveId = driveId,
            dataType = network.dataType,
            voiceType = network.voiceType,
            lteCells = formatLteCells(lteCells()),
        )
        scope.launch { dao.insert(sample) }
    }

    @SuppressLint("MissingPermission")
    private fun lteCells(): List<LteCell> {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return emptyList()
        return telephony.allCellInfo.orEmpty().filterIsInstance<CellInfoLte>().map { info ->
            val identity = info.cellIdentity
            LteCell(
                ci = identity.ci.orNullIfUnavailable(),
                pci = identity.pci.orNullIfUnavailable(),
                earfcn = identity.earfcn.orNullIfUnavailable(),
                bands = identity.bands.toList(),
                rsrp = info.cellSignalStrength.rsrp.orNullIfUnavailable(),
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateGps() {
        val shouldRun = driving.isDriving && hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        if (shouldRun == gpsOn) return
        if (shouldRun) {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                SAMPLE_INTERVAL_MS,
                0f,
                locationListener,
                Looper.getMainLooper(),
            )
        } else {
            locationManager.removeUpdates(locationListener)
        }
        gpsOn = shouldRun
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(Notifications.LOGGER_ID, Notifications.logger(this, notificationText()))
    }

    private fun notificationText(): String {
        val network = if (hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            mutableStatus.value.network?.label ?: "Waiting for network…"
        } else {
            "Needs phone permission"
        }
        return when {
            driving.isDriving -> "$network · driving"
            driving.inCar -> "$network · in car, location off"
            else -> network
        }
    }

    private fun hasPermission(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val EXTRA_FROM_BOOT = "from_boot"
        private const val SAMPLE_INTERVAL_MS = 30_000L
        private const val POLL_INTERVAL_MS = 60_000L
        private val PURGE_INTERVAL_MS = TimeUnit.DAYS.toMillis(1)
        private val RETENTION_MS = TimeUnit.DAYS.toMillis(90)

        private val mutableStatus = MutableStateFlow(LoggerStatus())
        val status: StateFlow<LoggerStatus> = mutableStatus

        fun start(context: Context, fromBoot: Boolean = false) {
            val intent = Intent(context, LoggerService::class.java).putExtra(EXTRA_FROM_BOOT, fromBoot)
            context.startForegroundService(intent)
        }
    }
}

private fun servingDbm(signalStrength: SignalStrength) =
    signalStrength.cellSignalStrengths.map { it.dbm }.firstOrNull { it != CellInfo.UNAVAILABLE }

private fun Int.orNullIfUnavailable() = takeIf { it != CellInfo.UNAVAILABLE }
