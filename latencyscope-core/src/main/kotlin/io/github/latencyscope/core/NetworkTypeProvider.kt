package io.github.latencyscope.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock

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

    @Volatile private var cached: String? = null
    @Volatile private var cachedAtElapsedMs: Long = Long.MIN_VALUE

    /**
     * The transport in use, re-read at most once every [CACHE_TTL_MS].
     *
     * This runs on the thread that just finished an HTTP call, once per
     * recorded call. `getNetworkCapabilities` is a binder round trip to
     * `system_server`, so querying it per call would put tens of microseconds
     * of IPC on the caller's network path — exactly the overhead this library
     * exists to measure. The radio does not change on a millisecond scale, so
     * a short cache costs no accuracy worth having; the steady-state cost is
     * one monotonic clock read and one volatile read.
     */
    fun current(): String? {
        val cm = connectivityManager ?: return null
        val now = SystemClock.elapsedRealtime()
        val lastRead = cachedAtElapsedMs
        if (lastRead != Long.MIN_VALUE && now - lastRead < CACHE_TTL_MS) return cached

        // A benign race here re-reads on two threads and stores the same value.
        val fresh = runCatching { readNetworkType(cm) }.getOrNull()
        cached = fresh
        cachedAtElapsedMs = now
        return fresh
    }

    // Lint cannot see that [hasPermission] already gated this: the manager is
    // null unless ACCESS_NETWORK_STATE was granted, so these calls are only
    // reachable with the permission held.
    @SuppressLint("MissingPermission")
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

        /** Long enough to drop the IPC off the hot path, short enough to follow a handover. */
        const val CACHE_TTL_MS = 1_000L
    }
}
