package com.wyatthui.unstick4g.logger

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.wyatthui.unstick4g.Settings

data class PairedDevice(val name: String, val address: String)

class CarTracker(
    private val context: Context,
    private val settings: Settings,
    private val onChange: (connected: Boolean) -> Unit,
) {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                ?: return
            if (device.address == settings.carAddress) {
                onChange(intent.action == BluetoothDevice.ACTION_ACL_CONNECTED)
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    fun recheck() = onChange(isCarConnectedNow())

    fun stop() {
        context.unregisterReceiver(receiver)
    }

    @SuppressLint("MissingPermission")
    private fun isCarConnectedNow(): Boolean {
        val address = settings.carAddress ?: return false
        val device = bondedDevices(context).firstOrNull { it.address == address } ?: return false
        return runCatching { BluetoothDevice::class.java.getMethod("isConnected").invoke(device) as Boolean }
            .getOrDefault(false)
    }
}

@SuppressLint("MissingPermission")
fun pairedDevices(context: Context) = bondedDevices(context).map { PairedDevice(it.name ?: it.address, it.address) }

@SuppressLint("MissingPermission")
private fun bondedDevices(context: Context): Set<BluetoothDevice> =
    context.getSystemService(BluetoothManager::class.java).adapter?.bondedDevices.orEmpty()
