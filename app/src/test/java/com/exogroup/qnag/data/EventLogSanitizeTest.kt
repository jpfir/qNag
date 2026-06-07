// SPDX-License-Identifier: GPL-3.0-or-later
package com.exogroup.qnag.data

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [EventLog.sanitize].
 *
 * Verifies that credentialed URLs are redacted, safe content is preserved, and
 * long messages are truncated.  These tests guard the shared Event Log against
 * leaking secrets to developers or support staff.
 */
class EventLogSanitizeTest {

    // ── Credentials removed ────────────────────────────────────────────────────

    @Test
    fun `http credentialed URL is redacted`() {
        val msg = "Fetching http://admin:secret@nagios.example.com/cgi-bin/status.cgi"
        val result = EventLog.sanitize(msg)
        assertFalse("Password must not appear", result.contains("secret"))
        assertFalse("Credentialed URL must not appear", result.contains("admin:secret@"))
        assertTrue("Redaction marker must be present", result.contains("[redacted-url]"))
    }

    @Test
    fun `https credentialed URL is redacted`() {
        val msg = "Poll https://user:p%40ssw0rd@monitor.corp.internal/api/v1"
        val result = EventLog.sanitize(msg)
        assertFalse("Password must not appear", result.contains("p%40ssw0rd"))
        assertTrue(result.contains("[redacted-url]"))
    }

    @Test
    fun `multiple credentialed URLs are all redacted`() {
        val msg = "http://a:b@host1.example.com failed, http://c:d@host2.example.com also failed"
        val result = EventLog.sanitize(msg)
        assertFalse(result.contains(":b@"))
        assertFalse(result.contains(":d@"))
        // Two separate redactions
        assertEquals(2, result.split("[redacted-url]").size - 1)
    }

    @Test
    fun `password-only fragment at-sign without scheme is not redacted`() {
        // "user@example.com" (no http:// prefix) must survive so ACK author names are kept
        val msg = "ACKed by oncall@example.com"
        val result = EventLog.sanitize(msg)
        assertTrue("Author email must survive sanitization", result.contains("oncall@example.com"))
        assertFalse(result.contains("[redacted-url]"))
    }

    // ── Safe content preserved ─────────────────────────────────────────────────

    @Test
    fun `URL without credentials is not redacted`() {
        val msg = "Fetching http://nagios.example.com/cgi-bin/statusjson.cgi"
        val result = EventLog.sanitize(msg)
        assertTrue("Safe URL must be preserved", result.contains("nagios.example.com"))
        assertFalse(result.contains("[redacted-url]"))
    }

    @Test
    fun `instance name is preserved`() {
        val result = EventLog.sanitize("Poll succeeded for instance Production-Nagios")
        assertTrue(result.contains("Production-Nagios"))
    }

    @Test
    fun `host and service names are preserved`() {
        val result = EventLog.sanitize("ACK submitted for host=web-01 service=HTTP")
        assertTrue(result.contains("web-01"))
        assertTrue(result.contains("HTTP"))
    }

    @Test
    fun `HTTP status code is preserved`() {
        val result = EventLog.sanitize("Command failed: HTTP 401: Unauthorized")
        assertTrue(result.contains("HTTP 401"))
    }

    @Test
    fun `command type is preserved`() {
        assertTrue(EventLog.sanitize("recheck command submitted").contains("recheck"))
        assertTrue(EventLog.sanitize("ACK submitted").contains("ACK"))
        assertTrue(EventLog.sanitize("downtime scheduled").contains("downtime"))
    }

    @Test
    fun `error messages without credentials are preserved`() {
        val msg = "Nagios rejected the ACK: not authorized. Check user permissions."
        val result = EventLog.sanitize(msg)
        assertTrue(result.contains("not authorized"))
        assertTrue(result.contains("Check user permissions"))
    }

    // ── Truncation ─────────────────────────────────────────────────────────────

    @Test
    fun `message over 500 characters is truncated to 500`() {
        val long = "x".repeat(600)
        assertEquals(500, EventLog.sanitize(long).length)
    }

    @Test
    fun `message under 500 characters is not truncated`() {
        val msg = "Short message"
        assertEquals(msg, EventLog.sanitize(msg))
    }

    @Test
    fun `empty message sanitizes to empty string`() {
        assertEquals("", EventLog.sanitize(""))
    }

    @Test
    fun `message exactly 500 characters is not truncated`() {
        val msg = "y".repeat(500)
        assertEquals(500, EventLog.sanitize(msg).length)
    }

    // ── Redaction + truncation interaction ────────────────────────────────────

    @Test
    fun `credentialed URL in long message is redacted before truncation`() {
        // The URL must be replaced, and the result must not exceed 500 chars
        val msg = "http://a:secret@host.example.com " + "x".repeat(600)
        val result = EventLog.sanitize(msg)
        assertFalse("Secret must not appear", result.contains("secret"))
        assertTrue(result.length <= 500)
    }

    // ── Authorization header ───────────────────────────────────────────────────

    @Test
    fun `Authorization Basic header is redacted`() {
        val msg = "Request failed — Authorization: Basic dXNlcjpzZWNyZXQ="
        val result = EventLog.sanitize(msg)
        assertFalse("Base64 credential must not appear", result.contains("dXNlcjpzZWNyZXQ="))
        assertTrue("Marker must be present", result.contains("[redacted]"))
        assertTrue("Authorization label should remain", result.contains("Authorization"))
    }

    @Test
    fun `Authorization Bearer token is redacted`() {
        val msg = "Polling failed: Authorization: Bearer eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0"
        val result = EventLog.sanitize(msg)
        assertFalse("JWT must not appear", result.contains("eyJhbGciOiJSUzI1NiJ9"))
        assertTrue(result.contains("[redacted]"))
    }

    @Test
    fun `Authorization header does not redact surrounding safe content`() {
        val msg = "Request to nagios.example.com failed\nAuthorization: Basic dXNlcjpzZWNyZXQ=\nHTTP 401: Unauthorized"
        val result = EventLog.sanitize(msg)
        assertTrue("Instance URL should remain", result.contains("nagios.example.com"))
        assertTrue("HTTP status should remain", result.contains("HTTP 401"))
        assertFalse("Credential must be gone", result.contains("dXNlcjpzZWNyZXQ="))
    }

    // ── Cookie / Set-Cookie headers ────────────────────────────────────────────

    @Test
    fun `Cookie header is redacted`() {
        val msg = "Request headers: Cookie: session=abc123; remember_me=token_xyz"
        val result = EventLog.sanitize(msg)
        assertFalse("Session value must not appear", result.contains("abc123"))
        assertTrue(result.contains("[redacted]"))
    }

    @Test
    fun `Set-Cookie header is redacted`() {
        val msg = "Response included Set-Cookie: session=secr3t; Path=/; HttpOnly"
        val result = EventLog.sanitize(msg)
        assertFalse("Cookie value must not appear", result.contains("secr3t"))
        assertTrue(result.contains("[redacted]"))
    }

    // ── X-Auth-Token header ────────────────────────────────────────────────────

    @Test
    fun `X-Auth-Token header is redacted`() {
        val msg = "Command rejected — X-Auth-Token: tok-abc123-xyz"
        val result = EventLog.sanitize(msg)
        assertFalse("Token must not appear", result.contains("tok-abc123-xyz"))
        assertTrue(result.contains("[redacted]"))
    }

    // ── URL query-string secrets ───────────────────────────────────────────────

    @Test
    fun `access_token query parameter is redacted`() {
        val msg = "GET /api/v1/status?access_token=s3cr3t-tok&format=json"
        val result = EventLog.sanitize(msg)
        assertFalse("Token value must not appear", result.contains("s3cr3t-tok"))
        assertTrue(result.contains("[redacted]"))
        assertTrue("Non-secret params must remain", result.contains("format=json"))
    }

    @Test
    fun `password query parameter is redacted`() {
        val msg = "POST /cmd.cgi?user=admin&password=hunter2&cmd_typ=50"
        val result = EventLog.sanitize(msg)
        assertFalse("Password must not appear", result.contains("hunter2"))
        assertTrue(result.contains("[redacted]"))
        assertTrue("Non-secret params must remain", result.contains("cmd_typ=50"))
    }

    @Test
    fun `passwd query parameter is redacted`() {
        val msg = "Login attempt: passwd=s3cret"
        val result = EventLog.sanitize(msg)
        assertFalse("Password must not appear", result.contains("s3cret"))
        assertTrue(result.contains("[redacted]"))
    }

    @Test
    fun `token query parameter is redacted`() {
        val msg = "Auth failed for ?token=abcdef1234&user=admin"
        val result = EventLog.sanitize(msg)
        assertFalse("Token value must not appear", result.contains("abcdef1234"))
        assertTrue(result.contains("[redacted]"))
        assertTrue("Non-secret params must remain", result.contains("user=admin"))
    }

    @Test
    fun `non-secret query parameters are not redacted`() {
        val msg = "Fetch: /statusjson.cgi?query=servicelist&details=true&servicestatus=warning"
        val result = EventLog.sanitize(msg)
        assertEquals("No redaction should occur", msg, result)
    }
}
