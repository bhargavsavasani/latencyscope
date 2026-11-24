package io.github.latencyscope.core

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import java.util.Locale

/** Reads device and app identity once at init, off the recording path. */
internal object DeviceInfoCollector {

    fun collect(context: Context, network: NetworkTypeProvider? = null): DeviceInfo {
        val appContext = context.applicationContext
        val packageInfo = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }.getOrNull()

        val (downstream, upstream) = network?.linkSpeedsKbps() ?: (null to null)

        return DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            appName = runCatching {
                appContext.packageManager.getApplicationLabel(appContext.applicationInfo).toString()
            }.getOrNull(),
            appId = appContext.packageName,
            appVersionName = packageInfo?.versionName,
            appVersionCode = packageInfo?.let { versionCodeOf(it) } ?: 0L,
            locale = Locale.getDefault().toString(),
            networkTypeAtStart = network?.current(),
            linkDownstreamKbps = downstream,
            linkUpstreamKbps = upstream,
        )
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
}
