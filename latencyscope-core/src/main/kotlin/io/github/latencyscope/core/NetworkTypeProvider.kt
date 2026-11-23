package io.github.latencyscope.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/**
 * Best-effort "what was the radio doing" label attached to each call.
 *
 * Needs `ACCESS_NETWORK_STATE`, which the library deliberately does not
 * declare: adding a permission to every consumer's manifest to decorate a
 * debug report is a bad trade. Apps that already hold it get the label, the
 * rest get null.
 */
internal class NetworkTypeProvider(context: Context) {

    private val appContext = context.applicationContext
    private val hasPermission: Boolean = appContext.checkPermission(
        Manifest.permission.ACCESS_NETWORK_STATE,
        android.os.Process.myPid(),
        android.os.Process.myUid(),
    ) == PackageManager.PERMISSION_GRANTED

    private val connectivityManager: ConnectivityManager? =
        if (!hasPermission) {
            null
        } else {
            runCatching {
                appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            }.getOrNull()
        }

    fun current(): String? {
        val cm = connectivityManager ?: return null
        return runCatching { readNetworkType(cm) }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun readNetworkType(cm: ConnectivityManager): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return NONE
            val caps = cm.getNetworkCapabilities(network) ?: return NONE
            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> CELLULAR
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ETHERNET
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> VPN
                else -> OTHER
            }
        }
        val info = cm.activeNetworkInfo ?: return NONE
        if (!info.isConnected) return NONE
        return when (info.type) {
            ConnectivityManager.TYPE_WIFI -> WIFI
            ConnectivityManager.TYPE_MOBILE -> CELLULAR
            ConnectivityManager.TYPE_ETHERNET -> ETHERNET
            ConnectivityManager.TYPE_VPN -> VPN
            else -> OTHER
        }
    }

    private companion object {
        const val WIFI = "wifi"
        const val CELLULAR = "cellular"
        const val ETHERNET = "ethernet"
        const val VPN = "vpn"
        const val NONE = "none"
        const val OTHER = "other"
    }
}
