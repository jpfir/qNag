# qNag Nagios Compatibility

## Tested Against

- Nagios Core 4.x with standard CGI configuration
- Nagios instances with and without reverse proxy (nginx/Apache)
- Instances using HTTP and HTTPS

---

## CGI Endpoints Used

qNag communicates with two Nagios CGI endpoints:

| Endpoint | Purpose |
|---|---|
| `statusjson.cgi` | Fetch host and service problem lists |
| `cmd.cgi` | Submit acknowledgements, rechecks, and downtime commands |

These are standard Nagios Core CGI endpoints. Nagios XI and compatible forks that expose the same CGIs should work, but have not been systematically tested.

---

## Supported Operations

| Operation | CGI | Notes |
|---|---|---|
| Fetch service problems | `statusjson.cgi?query=servicelist&details=true` | Filters `warning+critical+unknown` |
| Fetch host problems | `statusjson.cgi?query=hostlist&details=true` | Filters `down+unreachable` |
| Acknowledge service | `cmd.cgi` cmd_typ=34 | |
| Acknowledge host | `cmd.cgi` cmd_typ=33 | |
| Remove service ACK | `cmd.cgi` cmd_typ=52 | |
| Remove host ACK | `cmd.cgi` cmd_typ=51 | |
| Recheck service (forced) | `cmd.cgi` cmd_typ=7 + force_check=on | Uses UTC `start_time` |
| Recheck host (forced) | `cmd.cgi` cmd_typ=96 + force_check=on | Uses UTC `start_time` |
| Schedule service downtime | `cmd.cgi` cmd_typ=56 | Fixed downtime |
| Schedule host downtime | `cmd.cgi` cmd_typ=55 | Fixed downtime |
| Schedule host + services downtime | `cmd.cgi` cmd_typ=86 | Verify exact scope on your Nagios version |

---

## CSRF / NagFormId Handling

Nagios Core installations, especially with reverse proxies or security hardening, may use CSRF token validation via a `NagFormId` form field and cookie.

qNag performs a two-step command submission:

1. **Preflight GET** — fetches the `cmd.cgi` form page to obtain session cookies and the `NagFormId` field.
2. **POST** — submits the command with all form fields, cookies, and the `NagFormId` token.

All `Set-Cookie` values from the preflight are forwarded in the POST, including session tokens and reverse-proxy cookies. A `Referer` header is also sent because some proxy configurations require it.

If commands fail with CSRF errors, check:

- Your Nagios or proxy configuration requires a specific `Referer` header.
- The user account has sufficient command permissions.
- The `NagFormId` is being returned correctly by your installation.
- The reverse proxy is not stripping cookies or form fields.

---

## Command Permissions

For ACK, recheck, and downtime commands to succeed, the Nagios CGI user must have the relevant permissions configured in `cgi.cfg`.

Typical broad test configuration:

```text
authorized_for_all_hosts=nagiosadmin
authorized_for_all_services=nagiosadmin
authorized_for_all_host_commands=nagiosadmin
authorized_for_all_service_commands=nagiosadmin
authorized_for_system_commands=nagiosadmin
authorized_for_configuration_information=nagiosadmin
```

For production, prefer the least broad permission set that still allows the required host/service commands for the user's contacts/contact groups.

Read-only Nagios users cannot submit commands and will receive a permission error.

---

## Date Format for Recheck and Downtime

The `start_time` and `end_time` fields in recheck and downtime commands must match the `date_format` setting in `nagios.cfg`.

qNag defaults to `ISO8601` format: `yyyy-MM-dd HH:mm:ss`.

If your Nagios uses a different `date_format`, such as `us` or `euro`, change the setting in:

```text
Settings → Commands → Recheck date format
```

Supported formats:

| Setting | Format |
|---|---|
| ISO8601 (default) | `yyyy-MM-dd HH:mm:ss` |
| US | `MM-dd-yyyy HH:mm:ss` |
| Euro | `dd-MM-yyyy HH:mm:ss` |

qNag submits timestamps in UTC using the selected Nagios date format. If commands are accepted but scheduled at an unexpected time, verify the Nagios server timezone, `date_format`, and qNag's selected date format.

---

## Active / Passive / Freshness Check Fields

qNag parses the following fields from `statusjson.cgi` where available:

| Field | qNag use |
|---|---|
| `active_checks_enabled` | Shown as "Active checks: enabled/disabled" in details |
| `passive_checks_enabled` | Shown as "Passive checks: enabled/disabled" if returned |
| `check_freshness` | Shown as "Freshness checks" if returned |
| `freshness_threshold` | Shown as "Freshness threshold: Xs" if returned |
| `check_type` | Shown as "Last result: active/passive/other" |

**Important:** The `check_type` field describes the type of the **last check result**, not the configured check mode. A passive-only service may have `check_type=active` if it was last checked actively, for example after a manual forced recheck or a freshness-related action. Do not use `check_type` alone to determine whether a service is configured for passive checks.

Not all Nagios installations return all these fields. Missing fields are omitted from the UI cleanly.

---

## Reverse Proxy and Authentication

qNag uses HTTP Basic Auth for all Nagios API requests. Credentials are stored encrypted on-device and are never logged.

Reverse proxy setups may require:

- A specific `Host` header — usually handled automatically by OkHttp.
- A `Referer` header — sent by qNag.
- Cookie forwarding — handled by qNag's two-step command flow.
- Client certificates or custom auth headers — **not currently supported**.

If your Nagios is behind a proxy with non-standard authentication, test fetches and commands manually before relying on qNag for on-call monitoring.

---

## Security Notes

- qNag **never** logs passwords, Authorization headers, cookie values, or credentialed URLs.
- All error messages sanitize URL-like strings: `https://user@host/...` → `[redacted-url]`.
- The Event Log (`Settings → Event Log`) is safe to share with developers — it contains only instance names, problem counts, command kinds, and sanitized error messages.
- Credentials are stored using Android encrypted shared preferences backed by Android Keystore.

---

## Known Limitations

- Nagios XI APIs (REST) are not used; qNag talks only to the CGI layer.
- Icinga2 and other Nagios-compatible monitoring systems may work if they expose the same CGI interface, but are untested.
- LDAP/SAML/OAuth authentication in front of Nagios CGI is not supported.
- Client certificates and custom authentication headers are not currently supported.
- Very large Nagios installations with thousands of services may have slow status JSON responses.
- The `cmd_typ=86` command schedules downtime for a host and associated services according to Nagios Core CGI behavior. Verify the exact scope on your Nagios version before using it broadly.
