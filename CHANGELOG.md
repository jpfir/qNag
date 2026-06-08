# Changelog

All notable changes to qNag are documented here.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning uses [Semantic Versioning](https://semver.org/).

SPDX-License-Identifier: GPL-3.0-or-later

---

## [1.0.3] - unreleased

### Added

**Welcome screen**
- First-run experience: when the app starts with no configured instances it shows a Welcome
  screen instead of jumping straight into the Add Instance form.
- The Welcome screen shows the app name, a one-line description, a brief feature summary,
  the current version number, and two buttons — "Add Nagios instance" and "Import configuration".
- "Import configuration" on the Welcome screen reuses the existing import flow (SAF file
  picker → preview dialog → merge) so new users can restore a qNagstamon export in one step.
- After a successful add or import the app navigates directly to the Dashboard — no extra
  confirmation step required.
- Existing users with configured instances (enabled or disabled) never see the Welcome screen.

---

## [1.0.2] - 2026-06-08

### Added

**qNagstamon URL compatibility**
- qNag now accepts all Nagios URL forms that qNagstamon exports: bare host root, `/nagios`,
  `/nagios/cgi-bin`, and `/cgi-bin`. The configured URL is normalised to the CGI base at
  request time by a central `NagiosUrl` helper.
- Previously qNag always appended `/nagios/cgi-bin`, which produced invalid double-path URLs
  (`/nagios/cgi-bin/nagios/cgi-bin/statusjson.cgi`) for instances imported from qNagstamon.
- All CGI endpoints (statusjson, cmd.cgi, extinfo.cgi) use the same normalisation so behaviour
  is consistent across fetch, acknowledge, recheck, unack, downtime, and external detail links.
- Import/export URL values are stored and exported as-is; normalisation happens only at request
  time, keeping files round-trip compatible with both tools.

**Instance import / export**
- Import and export Nagios instance configurations from Settings → Instances.
- File format is the [qNagstamon](https://github.com/jfir/qNagstamon) v1 interchange format
  (`type = "qnagstamon.instances"`, `version = 1`) — files produced by either app can be
  read by the other.
- **Import**: pick a JSON file via the system file picker; a preview dialog shows how many
  instances will be added vs. updated before anything is saved. Existing instances are matched
  by normalised URL + username — unmatched instances are never deleted.
- **Export**: choose which instances to export; passwords are excluded by default and require
  explicit opt-in (a plaintext-warning banner is shown when enabled).
- Password security: passwords are never exported unless the user explicitly enables the
  include-passwords toggle; imported files without passwords preserve existing stored passwords;
  passwords are never written to Logcat, Toast, Snackbar, or exception messages.

**Dashboard instance chip filtering**
- Tapping a severity chip on a per-instance card in ALL mode now filters the alert list to
  that instance and that state (e.g. tap "CRITICALS 3" on prod-nagios to see only critical
  alerts from that instance).
- The quick-filter banner shows the instance name and state: "Showing: prod-nagios · Critical services".
- Tapping a global summary badge (D/U/C/W/N in the ALL header) still filters across all
  instances and clears any instance scope.
- Clearing the banner clears both filters.

**Notification mode settings**
- "Grouped details" mode is hidden from the notification display mode picker until it is
  implemented. Existing saved settings of GROUPED_DETAILS are transparently mapped to
  "Compact summary" on display; no data migration required.

**Empty-state / startup UX**
- When every configured instance is disabled, the Add Instance screen now shows a
  "Re-enable configured instance" button in addition to the normal Add form.
- Tapping it opens a picker listing all disabled instances by name and URL.
- Selecting one re-enables it (preserving all credentials and `notificationsEnabled`
  state), recalculates polling/Reliability-Mode scheduling, and opens its Dashboard.
- No change to the Add-new-instance flow or the Cancel button behaviour.

**Tier 2+ notification delay**
- Tier 2+ mode delays Android alerting and sound until an alert has remained active for a
  configurable duration — dashboard visibility is always immediate and unaffected.
- Configurable global Tier 2+ delay (default 5 minutes).
- Optional per-state delays: separate thresholds for host DOWN, host UNREACHABLE, service
  CRITICAL, service WARNING, and service UNKNOWN.
- Dashboard banner "Tier 2+ active · notify after Xm" when mode is enabled.
- Per-alert **T2+** badge on problem cards while an alert is waiting for notification eligibility.
- `ProblemAgeStore` — locally tracks first-seen timestamp per (instance, problem, status)
  when Nagios does not return `last_state_change`; entries pruned after 7 days.
- `AckAgeStore` — tracks when each ACKed alert was first seen as acknowledged; uses Nagios
  `acknowledgement_time` when available, local timestamp otherwise.
- ACK metadata fields on `NagiosProblem`: `acknowledgedBy`, `acknowledgementComment`,
  `acknowledgementTime` — parsed from `statusjson.cgi` where available.
- ACKed alert re-notification: ACKed alerts can optionally notify again after being ACKed
  for a configurable duration (disabled by default).
- `NotificationDecision` / `NotificationDecisionReason` — richer decision model replacing
  the boolean `shouldNotify()` path; existing callers kept via backward-compat wrapper.
- Event Log entries: Tier 2+ delay (new waiting alerts only), threshold reached, ACKed
  re-notification eligibility — no per-poll spam.

### Changed

**Notifications and sound**
- `evaluateNotificationDecision()` replaces `shouldNotify()` in foreground service and
  WorkManager paths; both paths share identical Tier 2+ eligibility logic.
- Alert sound is suppressed while Tier 2+ delay has not yet elapsed.
- Alert sound fires once when a problem crosses the Tier 2+ threshold, still subject to
  existing cooldown and fingerprint de-duplication rules.
- ACKed alerts remain quiet by default; re-notification requires explicit opt-in.
- `ACKED_RENOTIFY_ELIGIBLE` decision bypasses AckSuppressCache to avoid double-suppression.

**Dashboard**
- Problem list shows all alerts immediately; Tier 2+ only affects Android alerting/sound.

### Documentation

- `docs/reliability.md` — added Tier 2+ notification delay and ACKed re-notification sections.
- `docs/testing.md` — added Tier 2+ and ACKed re-notification regression checklists.

---

## [1.0.1] - unreleased

### Added

**Core monitoring**
- Multi-instance Nagios support — configure, enable/disable, and monitor multiple Nagios instances.
- ALL-instances dashboard mode — merged view across all enabled instances.
- Single-instance mode — focused view for one instance.
- Summary chips (D/U/C/W/N) with real-time counts per severity.
- Quick state filters — tap a summary chip to filter the problem list by state; tap again to clear.
- Section headers in the problem list grouped by severity.
- Sort explanation: "Severity first · newest within state".

**Alert actions**
- Acknowledge service and host problems.
- Host ACK with optional cascade to related service problems on the same host and instance.
- Remove acknowledgement for service and host problems.
- Forced recheck for service and host problems.
- Schedule fixed downtime for service, host, or host + all services.
- Multi-select mode — long press to select, then bulk ACK / recheck / remove ACK / downtime.
- ALL-mode command routing — each command goes to the correct instance.
- Command de-duplication — prevents duplicate submissions from rapid taps.
- Local ACK overlay — immediate UI feedback while Nagios confirms.

**Problem details**
- Full-screen details view with complete check timing metadata.
- Active/passive/freshness check configuration display.
- Open in Nagios (`extinfo.cgi`) browser link.
- Copy output / share alert.

**Reliability Mode**
- Foreground service polling — persistent notification, polls every 30–300 s.
- `START_STICKY` best-effort restart after OS kill.
- Exact Alarm Watchdog — independent `setExactAndAllowWhileIdle` recovery mechanism.
- WorkManager fallback — 15-minute background polling when foreground service is not active.
- Boot and package-update receiver — WorkManager rescheduled after device reboot.
- Stale monitoring detection — alert in Monitoring Health if no successful poll occurs within the threshold.
- One-notification design in foreground mode — alert summary embedded in the persistent notification.

**Notifications and sound**
- Notification channels for summary, fallback, and optional per-problem modes.
- SUMMARY_ONLY mode — one compact summary notification in background/fallback mode.
- PER_PROBLEM mode — individual per-alert notifications.
- In-app alert sound engine — independent of Android notification channel sound settings.
- Alarm audio stream option — can sound in vibration mode depending on Android/OEM/DND policy.
- Fingerprint-based sound de-duplication — unchanged alerts do not replay during cooldown.
- Severity escalation bypass — new critical alerts bypass normal cooldown and sound immediately.
- Notification visual state — colored large icon (green/amber/red/purple/orange) reflects worst current state.

**Settings and health**
- Settings organized into submenus with global search.
- Monitoring Health view — live status of all reliability layers with recovery actions.
- Per-instance notification toggle — clearly labeled "Android alerts" vs "Monitoring".
- Event Log — in-app log of polling, command, watchdog, and sound events; safe to share.
- Instance summary panel with expanded/collapsed persistence.
- Dashboard scope persistence — ALL / single instance remembered across restarts.

**Filters**
- Hide acknowledged, downtime, soft-state, notification-disabled, and check-disabled alerts.
- Host/service/status-info regex filters.

### Security

- Credentials stored using Android encrypted shared preferences backed by Android Keystore.
- No passwords, cookies, Authorization headers, or credentialed URLs are logged.
- Error messages sanitize URL-like strings.
- Event Log is designed to be safe to share with developers.

### Infrastructure

- GitHub Actions debug build workflow.
- GitHub Actions signed release workflow triggered on `v*` tags.
- `docs/testing.md` — production-readiness regression checklist.
- `docs/reliability.md` — Reliability Mode architecture and setup guide.
- `docs/nagios-compatibility.md` — CGI endpoint compatibility and configuration notes.
- GPL-3.0-or-later license.
