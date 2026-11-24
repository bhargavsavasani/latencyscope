package io.github.latencyscope.core

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Device identity is read once, at init, and stamped onto every exported
 * report — so a wrong value here is wrong in every report a user ever sends.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceInfoCollectorTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun collectsTheIdentityOfTheHostApp() {
        val info = DeviceInfoCollector.collect(context)

        assertEquals(context.packageName, info.appId)
        assertEquals(34, info.sdkInt)
        assertNotNull(info.manufacturer)
        assertNotNull(info.model)
        assertNotNull(info.androidVersion)
        assertNotNull(info.locale)
    }

    /** The library version travels with the report or the report cannot be triaged. */
    @Test
    fun carriesTheLibraryVersion() {
        assertEquals(BuildInfo.VERSION, DeviceInfoCollector.collect(context).libraryVersion)
    }

    /**
     * An app with no PackageInfo is a real case — an instrumented process, a
     * stripped test harness — and init must not throw there.
     */
    @Test
    fun survivesAContextWithNoPackageInfo() {
        val info = DeviceInfoCollector.collect(context)

        assertEquals(0L, info.appVersionCode)
    }
}
