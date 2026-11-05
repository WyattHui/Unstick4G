package com.wyatthui.unstick4g

import android.telephony.TelephonyManager

private val TWO_G_TYPES = setOf(
    TelephonyManager.NETWORK_TYPE_GPRS,
    TelephonyManager.NETWORK_TYPE_EDGE,
    TelephonyManager.NETWORK_TYPE_GSM,
)

private val TYPE_NAMES = mapOf(
    TelephonyManager.NETWORK_TYPE_UNKNOWN to "UNKNOWN",
    TelephonyManager.NETWORK_TYPE_GPRS to "GPRS",
    TelephonyManager.NETWORK_TYPE_EDGE to "EDGE",
    TelephonyManager.NETWORK_TYPE_GSM to "GSM",
    TelephonyManager.NETWORK_TYPE_UMTS to "UMTS",
    TelephonyManager.NETWORK_TYPE_HSDPA to "HSDPA",
    TelephonyManager.NETWORK_TYPE_HSUPA to "HSUPA",
    TelephonyManager.NETWORK_TYPE_HSPA to "HSPA",
    TelephonyManager.NETWORK_TYPE_HSPAP to "HSPA+",
    TelephonyManager.NETWORK_TYPE_LTE to "LTE",
    TelephonyManager.NETWORK_TYPE_NR to "NR",
    TelephonyManager.NETWORK_TYPE_IWLAN to "IWLAN",
)

data class NetworkSnapshot(val dataType: Int, val voiceType: Int) {
    val label: String
        get() = if (dataType == voiceType) {
            networkTypeName(dataType)
        } else {
            "${networkTypeName(dataType)} (voice ${networkTypeName(voiceType)})"
        }
}

fun isOn2g(dataType: Int, voiceType: Int) = dataType in TWO_G_TYPES || voiceType in TWO_G_TYPES

fun is4g(type: Int) = type == TelephonyManager.NETWORK_TYPE_LTE || type == TelephonyManager.NETWORK_TYPE_NR

fun networkTypeName(type: Int) = TYPE_NAMES[type] ?: "TYPE_$type"
