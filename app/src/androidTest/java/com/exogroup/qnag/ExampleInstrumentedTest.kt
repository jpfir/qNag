package com.exogroup.qnag

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Minimal instrumentation smoke test — verifies that the instrumentation framework is
 * wired correctly and the package name matches the declared application ID.
 *
 * This does NOT validate any qNag behaviour.  All business-logic tests are JVM unit tests
 * under src/test/ (run with ./gradlew test, no device required).  Real instrumentation tests
 * (SharedPreferences, notifications, sound, Nagios commands) can be added here once Robolectric
 * or an on-device test harness is in place.
 */
@RunWith(AndroidJUnit4::class)
class QNagInstrumentedSmokeTest {
    @Test
    fun instrumentationTargetsCorrectPackage() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.exogroup.qnag", appContext.packageName)
    }
}