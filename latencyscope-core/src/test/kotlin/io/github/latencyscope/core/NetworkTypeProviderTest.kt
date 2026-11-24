package io.github.latencyscope.core

import android.Manifest
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The network label is read once per recorded call, so its cost and its
 * failure behaviour both matter more than its precision.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkTypeProviderTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun grantTheOptionalPermission() {
        // The library never declares ACCESS_NETWORK_STATE; it only reads the
        // network when the host app already holds it. Most of these tests are
        // about the behaviour in apps that do.
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.ACCESS_NETWORK_STATE)
    }

    /**
     * The documented contract for apps that have not granted the permission:
     * no label, no crash, and no permission request on their behalf.
     */
    @Test
    fun withoutThePermissionItReportsNothingAtAll() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .denyPermissions(Manifest.permission.ACCESS_NETWORK_STATE)
        setTransport(NetworkCapabilities.TRANSPORT_WIFI)

        assertNull(NetworkTypeProvider(context).current())
    }

    @Test
    fun reportsTheActiveTransport() {
        setTransport(NetworkCapabilities.TRANSPORT_WIFI)

        assertEquals("wifi", NetworkTypeProvider(context).current())
    }

    @Test
    fun distinguishesCellularFromWifi() {
        setTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

        assertEquals("cellular", NetworkTypeProvider(context).current())
    }

    @Test
    fun reportsNoneWhenThereIsNoActiveNetwork() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(cm).setDefaultNetworkActive(false)
        shadowOf(cm).setActiveNetworkInfo(null)

        assertEquals("none", NetworkTypeProvider(context).current())
    }

    /**
     * The cache is the whole reason this class exists: without it every
     * recorded call pays a binder round trip to system_server. A second read
     * inside the TTL must not see a changed network.
     */
    @Test
    fun aSecondReadInsideTheTtlIsServedFromCache() {
        setTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val provider = NetworkTypeProvider(context)
        assertEquals("wifi", provider.current())

        setTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

        assertEquals("the second read must not have reached the framework", "wifi", provider.current())
    }

    private fun setTransport(transport: Int) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return
        val caps = NetworkCapabilities().also { shadowOf(it).addTransportType(transport) }
        shadowOf(cm).setNetworkCapabilities(network, caps)
    }
}
