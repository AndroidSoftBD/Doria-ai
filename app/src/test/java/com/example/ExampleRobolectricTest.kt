package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.actions.DeviceActionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Doria", appName)
    }

    @Test
    fun `test device action manager app validation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = DeviceActionManager(context)
        val result = manager.openApp("invalid_app_12345")
        // Should safely report false for unknown apps
        assertEquals(false, result.success)
    }

    @Test
    fun `test device action manager url validation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = DeviceActionManager(context)
        val result = manager.openUrl("javascript:alert(1)")
        // javascript pseudo-scheme must be rejected
        assertEquals(false, result.success)
    }

    @Test
    fun `test device action manager make call validation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = DeviceActionManager(context)
        val result = manager.makeCall("+919876543210")
        assertNotNull(result)
    }
}
