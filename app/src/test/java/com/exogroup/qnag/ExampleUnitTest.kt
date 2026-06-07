package com.exogroup.qnag

import com.exogroup.qnag.data.NagiosProblem
import com.exogroup.qnag.data.NagiosStatus
import com.exogroup.qnag.data.NotificationSettings
import com.exogroup.qnag.data.NotificationDecisionReason
import com.exogroup.qnag.data.evaluateNotificationDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smoke test: the notification filter compiles and evaluates correctly.
 * Per-topic tests live in src/test/java/com/exogroup/qnag/data/ and
 * src/test/java/com/exogroup/qnag/notifications/.
 */
class QNagSmokeTest {

    @Test
    fun `critical service with default settings is allowed`() {
        val problem = NagiosProblem.ServiceProblem(
            hostName = "host1",
            serviceName = "http",
            pluginOutput = "Connection refused",
            status = NagiosStatus.SERVICE_CRITICAL,
        )
        val decision = evaluateNotificationDecision("inst", problem, NotificationSettings())
        assertTrue(decision.shouldNotify)
        assertEquals(NotificationDecisionReason.ALLOWED, decision.reason)
    }

    @Test
    fun `warning service suppressed by default`() {
        val problem = NagiosProblem.ServiceProblem(
            hostName = "host1",
            serviceName = "disk",
            pluginOutput = "Disk 80% full",
            status = NagiosStatus.SERVICE_WARNING,
        )
        val decision = evaluateNotificationDecision("inst", problem, NotificationSettings())
        assertEquals(NotificationDecisionReason.STATE_DISABLED, decision.reason)
    }
}
