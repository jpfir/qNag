package com.exogroup.qnag.data

import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class NagiosApi {

    // Reused across all calls — do not create per-request
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // ── Fetch ─────────────────────────────────────────────────────────────────

    fun fetchProblems(instance: NagiosInstance): List<NagiosProblem> {
        val baseUrl = instance.url.trimEnd('/')
        val credential = Credentials.basic(instance.username, instance.password)

        val services = fetchServiceProblems(baseUrl, credential)
        val hosts = fetchHostProblems(baseUrl, credential)

        return (hosts + services).sortedWith(
            compareBy({ severityRank(it) }, { it.hostName }, { serviceNameOf(it) })
        )
    }

    private fun fetchServiceProblems(baseUrl: String, credential: String): List<NagiosProblem.ServiceProblem> {
        val url = "$baseUrl/nagios/cgi-bin/statusjson.cgi".toHttpUrl().newBuilder()
            .addEncodedQueryParameter("query", "servicelist")
            .addEncodedQueryParameter("details", "true")
            .addEncodedQueryParameter("servicestatus", "warning+critical+unknown")
            .build()

        val body = execute(url.toString(), credential)
        val serviceList = JSONObject(body)
            .optJSONObject("data")
            ?.optJSONObject("servicelist")
            ?: return emptyList()

        val result = mutableListOf<NagiosProblem.ServiceProblem>()
        val hostKeys = serviceList.keys()
        while (hostKeys.hasNext()) {
            val hostName = hostKeys.next()
            val services = serviceList.getJSONObject(hostName)
            val serviceKeys = services.keys()
            while (serviceKeys.hasNext()) {
                val serviceName = serviceKeys.next()
                val d = services.getJSONObject(serviceName)
                result += NagiosProblem.ServiceProblem(
                    hostName = hostName,
                    serviceName = serviceName,
                    pluginOutput = d.optString("plugin_output", "No output"),
                    status = d.optInt("status", NagiosStatus.SERVICE_UNKNOWN),
                    // Support both "problem_has_been_acknowledged" and "acknowledged" field names
                    acknowledged = d.optBooleanSafe("problem_has_been_acknowledged") ||
                            d.optBooleanSafe("acknowledged"),
                    notificationsEnabled = d.optBooleanSafe("notifications_enabled", default = true),
                    checksEnabled = d.optBooleanSafe("active_checks_enabled", default = true),
                    scheduledDowntimeDepth = d.optInt("scheduled_downtime_depth", 0),
                    isFlapping = d.optBooleanSafe("is_flapping"),
                    isSoftState = parseSoftState(d, "state_type"),
                    // TODO: host_status in service details may not be available in all Nagios versions
                    hostStatus = if (d.has("host_status")) d.optInt("host_status") else null,
                    hostAcknowledged = d.optBooleanSafe("host_problem_has_been_acknowledged") ||
                            d.optBooleanSafe("host_acknowledged"),
                    hostScheduledDowntimeDepth = d.optInt("host_scheduled_downtime_depth", 0),
                )
            }
        }
        return result
    }

    private fun fetchHostProblems(baseUrl: String, credential: String): List<NagiosProblem.HostProblem> {
        val url = "$baseUrl/nagios/cgi-bin/statusjson.cgi".toHttpUrl().newBuilder()
            .addEncodedQueryParameter("query", "hostlist")
            .addEncodedQueryParameter("details", "true")
            // TODO: Verify exact hoststatus parameter values for your Nagios version
            .addEncodedQueryParameter("hoststatus", "down+unreachable")
            .build()

        val body = execute(url.toString(), credential)
        val hostList = JSONObject(body)
            .optJSONObject("data")
            ?.optJSONObject("hostlist")
            ?: return emptyList()

        val result = mutableListOf<NagiosProblem.HostProblem>()
        val hostKeys = hostList.keys()
        while (hostKeys.hasNext()) {
            val hostName = hostKeys.next()
            val d = hostList.getJSONObject(hostName)
            result += NagiosProblem.HostProblem(
                hostName = hostName,
                pluginOutput = d.optString("plugin_output", "No output"),
                status = d.optInt("status", NagiosStatus.HOST_DOWN),
                acknowledged = d.optBooleanSafe("problem_has_been_acknowledged") ||
                        d.optBooleanSafe("acknowledged"),
                notificationsEnabled = d.optBooleanSafe("notifications_enabled", default = true),
                checksEnabled = d.optBooleanSafe("active_checks_enabled", default = true),
                scheduledDowntimeDepth = d.optInt("scheduled_downtime_depth", 0),
                isFlapping = d.optBooleanSafe("is_flapping"),
                isSoftState = parseSoftState(d, "state_type"),
            )
        }
        return result
    }

    // ── Commands (cmd.cgi) ────────────────────────────────────────────────────
    //
    // Two-step CSRF-aware command submission for modern Nagios Core:
    //   1. GET cmd.cgi?cmd_typ=<n>&cmd_mod=2  — capture NagFormId cookie + hidden field
    //   2. POST cmd.cgi with the form fields + cookie + nagFormId field
    //
    // If the GET response contains no NagFormId, falls back to direct POST (Nagios XI,
    // older Core without CSRF protection).
    //
    // ACK command types:   33 = ACKNOWLEDGE_HOST_PROBLEM, 34 = ACKNOWLEDGE_SVC_PROBLEM
    // Recheck command types: 98 = SCHEDULE_FORCED_HOST_CHECK, 54 = SCHEDULE_FORCED_SVC_CHECK
    //
    // start_time format depends on nagios.cfg date_format; default is US (MM-dd-yyyy HH:mm:ss).
    // TODO: verify date_format for your Nagios installation if recheck is silently ignored.

    fun acknowledgeProblem(
        instance: NagiosInstance,
        problem: NagiosProblem,
        settings: CommandSettings,
    ) {
        val baseUrl = instance.url.trimEnd('/')
        val credential = Credentials.basic(instance.username, instance.password)
        val cmdType = if (problem is NagiosProblem.HostProblem) "33" else "34"

        val body = FormBody.Builder()
            .add("cmd_mod", "2")
            .add("cmd_typ", cmdType)
            .add("host", problem.hostName)
            .add("com_author", settings.ackAuthor.ifBlank { "qNag" })
            .add("com_data", settings.defaultAckMessage.ifBlank { "Not critical or being worked on" })
            .apply {
                if (problem is NagiosProblem.ServiceProblem) add("service", problem.serviceName)
                if (settings.ackSticky) add("sticky_ack", "on")
                if (settings.ackNotify) add("send_notification", "on")
                if (settings.ackPersistent) add("persistent", "on")
            }
            .build()

        executeCommandWithCsrf("$baseUrl/nagios/cgi-bin/cmd.cgi", credential, body)
    }

    fun recheckProblem(instance: NagiosInstance, problem: NagiosProblem, settings: CommandSettings) {
        val baseUrl = instance.url.trimEnd('/')
        val credential = Credentials.basic(instance.username, instance.password)
        val cmdType = if (problem is NagiosProblem.HostProblem) "98" else "54"

        val startTime = SimpleDateFormat(settings.resolvedDateFormat.pattern, Locale.US).format(Date())

        val body = FormBody.Builder()
            .add("cmd_mod", "2")
            .add("cmd_typ", cmdType)
            .add("host", problem.hostName)
            .add("start_time", startTime)
            .add("force_check", "on")
            .apply {
                if (problem is NagiosProblem.ServiceProblem) add("service", problem.serviceName)
            }
            .build()

        executeCommandWithCsrf("$baseUrl/nagios/cgi-bin/cmd.cgi", credential, body)
    }

    // Convenience wrappers for bulk operations
    fun acknowledgeProblems(
        instance: NagiosInstance,
        problems: List<NagiosProblem>,
        settings: CommandSettings,
    ) = problems.forEach { acknowledgeProblem(instance, it, settings) }

    fun recheckProblems(
        instance: NagiosInstance,
        problems: List<NagiosProblem>,
        settings: CommandSettings,
    ) = problems.forEach { recheckProblem(instance, it, settings) }

    // ── CSRF-aware command execution ──────────────────────────────────────────

    /**
     * Two-step command flow:
     *  1. GET cmd.cgi with cmd_typ/cmd_mod to obtain the NagFormId CSRF token.
     *  2. POST with the CSRF cookie + nagFormId field (or direct POST if no token found).
     */
    private fun executeCommandWithCsrf(cmdUrl: String, credential: String, body: FormBody) {
        // ── Step 1: GET to obtain CSRF token ─────────────────────────────────
        // Include cmd_typ, host, service in the preflight so Nagios renders the correct form.
        // Intentionally exclude cmd_mod=2 and comment/data fields — those go only in the POST.
        val prefightParams = setOf("cmd_typ", "host", "service")
        val getUrlBuilder = cmdUrl.toHttpUrl().newBuilder()
        for (i in 0 until body.size) {
            if (body.name(i) in prefightParams) {
                getUrlBuilder.addQueryParameter(body.name(i), body.value(i))
            }
        }

        val getRequest = Request.Builder()
            .url(getUrlBuilder.build())
            .header("Authorization", credential)
            .get()
            .build()

        val (csrfCookie, csrfField) = client.newCall(getRequest).execute().use { response ->
            val cookie = response.headers("Set-Cookie")
                .firstOrNull { it.startsWith("NagFormId=", ignoreCase = true) }
                ?.substringAfter("=")?.substringBefore(";")?.trim()
            val html = response.body?.string() ?: ""
            cookie to parseCsrfField(html)
        }

        // If the hidden field is absent but the cookie exists, use the cookie value as fallback.
        val effectiveCsrfField = csrfField ?: csrfCookie

        // ── Step 2: POST with CSRF token if present ───────────────────────────
        val postBodyBuilder = FormBody.Builder()
        for (i in 0 until body.size) {
            postBodyBuilder.add(body.name(i), body.value(i))
        }
        if (effectiveCsrfField != null) {
            postBodyBuilder.add("nagFormId", effectiveCsrfField)
        }
        postBodyBuilder.add("btnSubmit", "Commit")

        val postRequest = Request.Builder()
            .url(cmdUrl)
            .header("Authorization", credential)
            .apply { if (csrfCookie != null) header("Cookie", "NagFormId=$csrfCookie") }
            .post(postBodyBuilder.build())
            .build()

        client.newCall(postRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${response.message}")
            }
            val text = response.body?.string() ?: ""
            if (containsNagiosError(text)) {
                throw Exception("Nagios rejected the command — check user permissions.")
            }
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Parse the nagFormId hidden field from Nagios Core HTML.
     * Handles both attribute orderings (name-then-value and value-then-name).
     */
    private fun parseCsrfField(html: String): String? {
        val p1 = Regex(
            """<input[^>]+name\s*=\s*["']nagFormId["'][^>]+value\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        val p2 = Regex(
            """<input[^>]+value\s*=\s*["']([^"']+)["'][^>]+name\s*=\s*["']nagFormId["']""",
            RegexOption.IGNORE_CASE
        )
        return (p1.find(html) ?: p2.find(html))?.groupValues?.getOrNull(1)
    }

    private fun execute(url: String, credential: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", credential)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${response.message}")
            }
            return response.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw Exception("Empty response from Nagios")
        }
    }

    // Nagios returns HTTP 200 even for errors; look for error markers in HTML.
    private fun containsNagiosError(html: String): Boolean {
        val lower = html.lowercase()
        return lower.contains("error:") ||
                lower.contains("not authorized") ||
                lower.contains("invalid command") ||
                lower.contains("you do not have permission") ||
                lower.contains("csrf") ||
                lower.contains("invalid or missing")
    }

    // Nagios XI: state_type is integer (0=SOFT, 1=HARD); older versions may use strings.
    private fun parseSoftState(obj: JSONObject, key: String): Boolean {
        val raw = obj.opt(key) ?: return false
        return when {
            raw is Int -> raw == 0
            raw is Long -> raw == 0L
            else -> raw.toString().let { s -> s == "0" || s.equals("SOFT", ignoreCase = true) }
        }
    }

    /**
     * Safe boolean parsing that handles int (0/1), Boolean, and String representations.
     * Nagios versions vary in how they encode boolean fields in statusjson.
     */
    private fun JSONObject.optBooleanSafe(key: String, default: Boolean = false): Boolean {
        return when (val v = opt(key)) {
            null -> default
            is Boolean -> v
            is Int -> v != 0
            is Long -> v != 0L
            is String -> v == "1" || v.equals("true", ignoreCase = true)
            else -> default
        }
    }

    private fun severityRank(p: NagiosProblem): Int = when {
        p is NagiosProblem.HostProblem && p.status == NagiosStatus.HOST_DOWN -> 0
        p is NagiosProblem.ServiceProblem && p.status == NagiosStatus.SERVICE_CRITICAL -> 1
        p is NagiosProblem.HostProblem && p.status == NagiosStatus.HOST_UNREACHABLE -> 2
        p is NagiosProblem.ServiceProblem && p.status == NagiosStatus.SERVICE_UNKNOWN -> 3
        p is NagiosProblem.ServiceProblem && p.status == NagiosStatus.SERVICE_WARNING -> 4
        else -> 5
    }

    private fun serviceNameOf(p: NagiosProblem): String =
        if (p is NagiosProblem.ServiceProblem) p.serviceName else ""
}
