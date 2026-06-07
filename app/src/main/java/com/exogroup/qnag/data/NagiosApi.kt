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
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class NagiosApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // ── Fetch ─────────────────────────────────────────────────────────────────

    fun fetchProblems(instance: NagiosInstance): List<NagiosProblem> {
        val baseUrl = instance.url.trimEnd('/')
        val credential = Credentials.basic(instance.username, instance.password)

        // Tag each problem with its source instance so the dashboard and notifications
        // can distinguish problems from different instances in ALL mode.
        val services = fetchServiceProblems(baseUrl, credential)
            .map { it.copy(instanceId = instance.id, instanceName = instance.name) }
        val hosts = fetchHostProblems(baseUrl, credential)
            .map { it.copy(instanceId = instance.id, instanceName = instance.name) }

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
                    acknowledged = d.optBooleanSafe("problem_has_been_acknowledged") ||
                            d.optBooleanSafe("acknowledged"),
                    notificationsEnabled = d.optBooleanSafe("notifications_enabled", default = true),
                    checksEnabled = d.optBooleanSafe("active_checks_enabled", default = true),
                    scheduledDowntimeDepth = d.optInt("scheduled_downtime_depth", 0),
                    isFlapping = d.optBooleanSafe("is_flapping"),
                    isSoftState = parseSoftState(d, "state_type"),
                    hostStatus = if (d.has("host_status")) d.optInt("host_status") else null,
                    hostAcknowledged = d.optBooleanSafe("host_problem_has_been_acknowledged") ||
                            d.optBooleanSafe("host_acknowledged"),
                    hostScheduledDowntimeDepth = d.optInt("host_scheduled_downtime_depth", 0),
                    // Check timing metadata (optional — Nagios may not return all fields)
                    lastCheck          = d.optEpochMs("last_check"),
                    nextCheck          = d.optEpochMs("next_check"),
                    lastStateChange    = d.optEpochMs("last_state_change"),
                    lastHardStateChange = d.optEpochMs("last_hard_state_change"),
                    currentAttempt     = d.optInt("current_attempt", 0).takeIf { it > 0 },
                    maxAttempts        = d.optInt("max_check_attempts", 0).takeIf { it > 0 },
                    checkType          = d.optCheckType("check_type"),
                )
            }
        }
        return result
    }

    private fun fetchHostProblems(baseUrl: String, credential: String): List<NagiosProblem.HostProblem> {
        val url = "$baseUrl/nagios/cgi-bin/statusjson.cgi".toHttpUrl().newBuilder()
            .addEncodedQueryParameter("query", "hostlist")
            .addEncodedQueryParameter("details", "true")
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
                // Check timing metadata
                lastCheck          = d.optEpochMs("last_check"),
                nextCheck          = d.optEpochMs("next_check"),
                lastStateChange    = d.optEpochMs("last_state_change"),
                lastHardStateChange = d.optEpochMs("last_hard_state_change"),
                currentAttempt     = d.optInt("current_attempt", 0).takeIf { it > 0 },
                maxAttempts        = d.optInt("max_check_attempts", 0).takeIf { it > 0 },
                checkType          = d.optCheckType("check_type"),
            )
        }
        return result
    }

    // ── Commands (cmd.cgi) ────────────────────────────────────────────────────
    //
    // CSRF-aware two-step flow (where needed):
    //   1. GET cmd.cgi?cmd_typ=<n>&host=<h>[&service=<s>]  — capture session cookies + NagFormId
    //   2. POST cmd.cgi with all form fields + cookies + nagFormId
    //
    // qNagstamon uses direct POST without a preflight (no CSRF) and cmd_typ=7/96 with force_check=on.
    // We keep the preflight to support CSRF-protected Nagios Core, but use the same cmd_typ values.
    //
    // ACK command types:       33 = ACKNOWLEDGE_HOST_PROBLEM, 34 = ACKNOWLEDGE_SVC_PROBLEM
    // Recheck command types:   7  = CMD_SCHEDULE_SVC_CHECK (+ force_check=on for forced)
    //                          96 = CMD_SCHEDULE_HOST_CHECK (+ force_check=on for forced)
    //
    // start_time is formatted in UTC to match qNagstamon behavior.
    // The default date format is ISO8601 ("yyyy-MM-dd HH:mm:ss") which matches nagios.cfg
    // date_format=iso8601.  Change the setting if your server uses a different date_format.

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

        executeCommandWithCsrf("$baseUrl/nagios/cgi-bin/cmd.cgi", credential, body, "ACK", settings)
    }

    fun recheckProblem(instance: NagiosInstance, problem: NagiosProblem, settings: CommandSettings) {
        val baseUrl = instance.url.trimEnd('/')
        val credential = Credentials.basic(instance.username, instance.password)

        // Use cmd_typ=7 (CMD_SCHEDULE_SVC_CHECK) for services and cmd_typ=96
        // (CMD_SCHEDULE_HOST_CHECK) for hosts, matching qNagstamon exactly.
        // force_check=on makes this a "forced" recheck through the web form.
        // The old 54/98 IDs are the raw Nagios command numbers and fail with CSRF-protected
        // Nagios Core because the form for 54/98 expects different field sets.
        val cmdType = if (problem is NagiosProblem.HostProblem) "96" else "7"

        // Format start_time in UTC, matching qNagstamon:
        //   datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")
        val sdf = SimpleDateFormat(settings.resolvedDateFormat.pattern, Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val startTime = sdf.format(Date())

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

        executeCommandWithCsrf("$baseUrl/nagios/cgi-bin/cmd.cgi", credential, body, "recheck", settings)
    }

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

    private fun executeCommandWithCsrf(
        cmdUrl: String,
        credential: String,
        body: FormBody,
        commandKind: String = "command",
        settings: CommandSettings? = null,
    ) {
        val debug = settings?.debugCommandSubmission == true

        // ── Preflight GET: obtain session cookies and CSRF token ──────────────
        // Include cmd_typ, host, service — Nagios needs them to render the correct form.
        // Exclude cmd_mod=2 and comment/data fields (those go only in the POST).
        val preflightParams = setOf("cmd_typ", "host", "service")
        val getUrl = cmdUrl.toHttpUrl().newBuilder().apply {
            for (i in 0 until body.size) {
                if (body.name(i) in preflightParams) addQueryParameter(body.name(i), body.value(i))
            }
        }.build()

        if (debug) {
            val fieldNames = (0 until body.size).map { body.name(it) }
            android.util.Log.d("qNag", "[$commandKind] preflight GET cmd_typ=${getUrl.queryParameter("cmd_typ")} bodyFields=$fieldNames")
        }

        val csrf = client.newCall(
            Request.Builder().url(getUrl).header("Authorization", credential).get().build()
        ).execute().use { response ->
            // Collect ALL Set-Cookie values — session cookies, NagFormId, reverse-proxy tokens
            val cookies = response.headers("Set-Cookie").mapNotNull { raw ->
                val name = raw.substringBefore("=").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val value = raw.substringAfter("=").substringBefore(";").trim()
                name to value
            }
            val html = response.body?.string() ?: ""
            val nagFormIdFromField = parseCsrfField(html)
            val nagFormIdFromCookie = cookies.find { (n, _) -> n.equals("NagFormId", ignoreCase = true) }?.second
            val cookieHeader = cookies.joinToString("; ") { (n, v) -> "$n=$v" }

            if (debug) android.util.Log.d("qNag",
                "[$commandKind] preflight status=${response.code} cookies=${cookies.size} nagFormId(field)=${nagFormIdFromField != null} nagFormId(cookie)=${nagFormIdFromCookie != null}")

            CsrfData(cookieHeader, nagFormIdFromField ?: nagFormIdFromCookie)
        }

        // ── POST ──────────────────────────────────────────────────────────────
        val postBody = FormBody.Builder().apply {
            for (i in 0 until body.size) add(body.name(i), body.value(i))
            if (csrf.nagFormId != null) add("nagFormId", csrf.nagFormId)
            add("btnSubmit", "Commit")
        }.build()

        if (debug) {
            val postFields = (0 until postBody.size).map { postBody.name(it) }
            android.util.Log.d("qNag", "[$commandKind] POST fields=$postFields hasCookies=${csrf.cookieHeader.isNotEmpty()}")
        }

        val postRequest = Request.Builder()
            .url(cmdUrl)
            .header("Authorization", credential)
            .header("Referer", cmdUrl)   // qNagstamon sends Referer; some proxy configs require it
            .apply { if (csrf.cookieHeader.isNotEmpty()) header("Cookie", csrf.cookieHeader) }
            .post(postBody)
            .build()

        client.newCall(postRequest).execute().use { response ->
            val text = response.body?.string() ?: ""
            if (debug) android.util.Log.d("qNag",
                "[$commandKind] POST status=${response.code} snippet=${sanitizeForLog(text)}")

            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${response.message}")
            }
            classifyNagiosResponse(text, commandKind)
        }
    }

    /**
     * Classify the Nagios cmd.cgi response and throw a specific exception on failure.
     *
     * Nagios returns HTTP 200 even for errors; the actual result is in the HTML body.
     * Response categories (most specific first):
     *  - Explicit success: "successfully submitted" → return normally
     *  - CSRF: "invalid or missing csrf" / nagformid + invalid → CSRF error
     *  - Permission: "not authorized" / "do not have permission" / "read only"
     *  - Field / date format: "go back and verify" / "required information" / "bad format"
     *  - Generic error marker: "error:"
     *  - Unknown / no match → assume success (some Nagios versions redirect or use custom text)
     */
    private fun classifyNagiosResponse(html: String, commandKind: String) {
        val lower = html.lowercase()
        if (lower.contains("successfully submitted")) return

        if (lower.contains("invalid or missing csrf") ||
            (lower.contains("nagformid") && (lower.contains("invalid") || lower.contains("missing")))) {
            throw Exception("Nagios rejected the CSRF form ID/cookie. Try again.")
        }
        if (lower.contains("not authorized") ||
            lower.contains("do not have permission") ||
            lower.contains("read only")) {
            throw Exception("Nagios rejected the $commandKind: not authorized. Check user permissions.")
        }
        if (lower.contains("go back and verify") ||
            lower.contains("required information") ||
            lower.contains("bad format")) {
            throw Exception("Nagios rejected the $commandKind: a required field or date format was invalid. Check the 'Nagios date format' setting in qNag.")
        }
        if (lower.contains("error:") && !lower.contains("successfully")) {
            throw Exception("Nagios returned an error for the $commandKind.")
        }
        // No recognisable error — treat as success (redirect, custom theme, or confirmation page)
    }

    /**
     * Parse the nagFormId hidden field from Nagios Core HTML.
     * Handles both attribute orderings.
     */
    private fun parseCsrfField(html: String): String? {
        val p1 = Regex("""<input[^>]+name\s*=\s*["']nagFormId["'][^>]+value\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val p2 = Regex("""<input[^>]+value\s*=\s*["']([^"']+)["'][^>]+name\s*=\s*["']nagFormId["']""", RegexOption.IGNORE_CASE)
        return (p1.find(html) ?: p2.find(html))?.groupValues?.getOrNull(1)
    }

    private fun execute(url: String, credential: String): String {
        val request = Request.Builder().url(url).header("Authorization", credential).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}: ${response.message}")
            return response.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw Exception("Empty response from Nagios")
        }
    }

    private fun sanitizeForLog(text: String): String =
        text.take(300)
            .replace(Regex("https?://[^/\\s]*@[^/\\s]*"), "[redacted-url]")
            .replace(Regex("\\s+"), " ")

    /**
     * Parse a Nagios timestamp field to epoch milliseconds.
     *
     * Nagios statusjson returns timestamps as epoch seconds (10-digit) or occasionally millis
     * (13-digit).  Handles Int, Long, Double, and numeric String values gracefully.
     * Returns null for missing, zero, or unparseable values.
     */
    private fun JSONObject.optEpochMs(key: String): Long? {
        val raw = opt(key) ?: return null
        val num: Long = when (raw) {
            is Int    -> raw.toLong()
            is Long   -> raw
            is Double -> raw.toLong()
            is String -> raw.toLongOrNull() ?: return null
            else      -> return null
        }
        if (num <= 0) return null
        // 10-digit = epoch seconds; threshold ~year 2286 as seconds
        return if (num < 10_000_000_000L) num * 1000L else num
    }

    /** Parse check_type field: 0 = active, 1 = passive. */
    private fun JSONObject.optCheckType(key: String): String? = when (optInt(key, -1)) {
        0 -> "active"
        1 -> "passive"
        else -> null
    }

    private fun parseSoftState(obj: JSONObject, key: String): Boolean {
        val raw = obj.opt(key) ?: return false
        return when {
            raw is Int -> raw == 0
            raw is Long -> raw == 0L
            else -> raw.toString().let { s -> s == "0" || s.equals("SOFT", ignoreCase = true) }
        }
    }

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

    private data class CsrfData(val cookieHeader: String, val nagFormId: String?)
}
