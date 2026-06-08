package com.exogroup.qnag.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NagiosUrlTest {

    // ── cgiBase: root forms ───────────────────────────────────────────────────

    @Test
    fun `root without trailing slash`() {
        assertEquals(
            "https://nagios.example.com/nagios/cgi-bin",
            NagiosUrl.cgiBase("https://nagios.example.com")
        )
    }

    @Test
    fun `root with trailing slash`() {
        assertEquals(
            "https://nagios.example.com/nagios/cgi-bin",
            NagiosUrl.cgiBase("https://nagios.example.com/")
        )
    }

    @Test
    fun `leading and trailing whitespace is stripped`() {
        assertEquals(
            "https://nagios.example.com/nagios/cgi-bin",
            NagiosUrl.cgiBase("  https://nagios.example.com  ")
        )
    }

    // ── cgiBase: /nagios forms ────────────────────────────────────────────────

    @Test
    fun `nagios path without trailing slash`() {
        assertEquals(
            "https://nagios.example.com/nagios/cgi-bin",
            NagiosUrl.cgiBase("https://nagios.example.com/nagios")
        )
    }

    @Test
    fun `nagios path with trailing slash`() {
        assertEquals(
            "https://nagios.example.com/nagios/cgi-bin",
            NagiosUrl.cgiBase("https://nagios.example.com/nagios/")
        )
    }

    // ── cgiBase: /nagios/cgi-bin forms ───────────────────────────────────────

    @Test
    fun `nagios cgi-bin path without trailing slash`() {
        assertEquals(
            "https://nagios.example.com/nagios/cgi-bin",
            NagiosUrl.cgiBase("https://nagios.example.com/nagios/cgi-bin")
        )
    }

    @Test
    fun `nagios cgi-bin path with trailing slash`() {
        assertEquals(
            "https://nagios.example.com/nagios/cgi-bin",
            NagiosUrl.cgiBase("https://nagios.example.com/nagios/cgi-bin/")
        )
    }

    // ── cgiBase: bare /cgi-bin forms ─────────────────────────────────────────

    @Test
    fun `bare cgi-bin path without trailing slash`() {
        assertEquals(
            "https://nagios.example.com/cgi-bin",
            NagiosUrl.cgiBase("https://nagios.example.com/cgi-bin")
        )
    }

    @Test
    fun `bare cgi-bin path with trailing slash`() {
        assertEquals(
            "https://nagios.example.com/cgi-bin",
            NagiosUrl.cgiBase("https://nagios.example.com/cgi-bin/")
        )
    }

    // ── cgiBase: normalise away trailing script or query/fragment ────────────

    @Test
    fun `full cgi script path normalised to cgi-bin directory`() {
        assertEquals(
            "https://nagios.example.com/nagios/cgi-bin",
            NagiosUrl.cgiBase("https://nagios.example.com/nagios/cgi-bin/statusjson.cgi")
        )
    }

    @Test
    fun `query string is stripped`() {
        assertEquals(
            "https://nagios.example.com/nagios/cgi-bin",
            NagiosUrl.cgiBase("https://nagios.example.com/nagios/cgi-bin?x=1")
        )
    }

    @Test
    fun `fragment is stripped`() {
        assertEquals(
            "https://nagios.example.com/nagios/cgi-bin",
            NagiosUrl.cgiBase("https://nagios.example.com/nagios/cgi-bin#abc")
        )
    }

    @Test
    fun `query and fragment both stripped`() {
        assertEquals(
            "https://nagios.example.com/nagios/cgi-bin",
            NagiosUrl.cgiBase("https://nagios.example.com/nagios/cgi-bin?x=1#abc")
        )
    }

    // ── cgiBase: http scheme ──────────────────────────────────────────────────

    @Test
    fun `http scheme preserved`() {
        assertEquals(
            "http://nagios.example.com/nagios/cgi-bin",
            NagiosUrl.cgiBase("http://nagios.example.com/")
        )
    }

    // ── cgiBase: non-standard port ────────────────────────────────────────────

    @Test
    fun `non-standard port preserved`() {
        assertEquals(
            "https://nagios.example.com:8080/nagios/cgi-bin",
            NagiosUrl.cgiBase("https://nagios.example.com:8080/")
        )
    }

    @Test
    fun `non-standard port with cgi-bin path`() {
        assertEquals(
            "https://nagios.example.com:8443/nagios/cgi-bin",
            NagiosUrl.cgiBase("https://nagios.example.com:8443/nagios/cgi-bin")
        )
    }

    // ── cgi: script appending ─────────────────────────────────────────────────

    @Test
    fun `cgi appends script to normalized cgi-bin base`() {
        assertEquals(
            "https://nagios.example.com/nagios/cgi-bin/statusjson.cgi",
            NagiosUrl.cgi("https://nagios.example.com", "statusjson.cgi")
        )
    }

    @Test
    fun `cgi with cgi-bin url appends script without duplication`() {
        assertEquals(
            "https://nagios.example.com/nagios/cgi-bin/cmd.cgi",
            NagiosUrl.cgi("https://nagios.example.com/nagios/cgi-bin", "cmd.cgi")
        )
    }

    @Test
    fun `cgi with nagios url appends cgi-bin then script`() {
        assertEquals(
            "https://nagios.example.com/nagios/cgi-bin/extinfo.cgi",
            NagiosUrl.cgi("https://nagios.example.com/nagios", "extinfo.cgi")
        )
    }

    @Test
    fun `cgi with leading slash in script name`() {
        assertEquals(
            "https://nagios.example.com/nagios/cgi-bin/statusjson.cgi",
            NagiosUrl.cgi("https://nagios.example.com", "/statusjson.cgi")
        )
    }

    // ── No duplicate /nagios/cgi-bin ─────────────────────────────────────────

    @Test
    fun `no duplicate nagios cgi-bin when url already contains it`() {
        val result = NagiosUrl.cgi("https://nagios.example.com/nagios/cgi-bin", "statusjson.cgi")
        val count = result.split("/nagios/cgi-bin").size - 1
        assertEquals("Expected exactly one /nagios/cgi-bin in: $result", 1, count)
    }

    @Test
    fun `no duplicate cgi-bin when url already has bare cgi-bin`() {
        val result = NagiosUrl.cgi("https://nagios.example.com/cgi-bin", "statusjson.cgi")
        val count = result.split("cgi-bin").size - 1
        assertEquals("Expected exactly one cgi-bin in: $result", 1, count)
    }
}
