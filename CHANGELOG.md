# Changelog

All notable changes to qNag are documented here.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning uses [Semantic Versioning](https://semver.org/).

SPDX-License-Identifier: GPL-3.0-or-later

---

## [1.0.6] - 2026-06-26

### Added
- **Home Screen Widgets:** Compact and Large status widgets powered by Glance (Note: Read-only in this release).
- **Wearable Notifications:** Condensed formatting optimized for mirrored smartwatch and fitness band screens.
- **Notification Privacy:** Option to hide sensitive host/service names on lock screens and wearables.
- **Refresh Failure Alerts:** Explicit visual indicators when qNag fails to reach or poll an instance.
- **Settings Search:** Find settings instantly by keyword.
- **Command Tracking:** Real-time progress indicators for running commands (ACK, Recheck, Downtime).

### Changed
- **Settings Overhaul:** Reorganized the settings menu into dedicated, focused sub-pages.
- **Swipe Gestures:** Rebuilt swipe-to-ACK/Recheck logic so cards no longer get visually stuck.
- **Problem Hierarchy:** Standarized sorting across the app, widgets, and notifications to strictly prioritize DOWN / CRITICAL / UNREACHABLE.

### Fixed
- Data desync between background workers, the main app, and active notifications.
- Problems marked NOTIF OFF in Nagios failing to trigger standard app alerts.

### Notes
Wearable support relies on standard Android notification mirroring; there is no native Wear OS app/Tile in this release.

## [1.0.5] - 2026-06-26

### Added
- **Command Activity:** Added a dedicated screen to track recent user-triggered commands (ACK, Remove ACK, Recheck, and Downtime) displaying per-target progress and final success/failure states.
- **Active Command Indicator:** Added a live dashboard banner displayed whenever background commands are actively running.

### Changed
- **Dashboard Refresh Stability:** Refreshes now preserve the user's scroll position and display a stale-data overlay rather than a hard loading screen.
- **State Fingerprinting:** Implemented display fingerprints to prevent unnecessary UI recomposition, list replacement, and visual jitter when incoming data has not materially changed.
- **App Resume Behavior:** Throttled automatic background refreshes upon app resume if data was fetched recently (manual pull-to-refresh remains unthrottled).
- **Swipe Gestures:** Calibrated swipe-to-ACK and Recheck thresholds to prevent accidental triggers during vertical scrolling; swiping is now strictly locked out during multi-selection mode.

### Fixed
- **Silent NOTIF OFF Alerts:** Instances flagged with Nagios notifications disabled now correctly populate on the dashboard while strictly suppressing device alerts (sound, vibration, push, and wearable).
- **Background Tracking:** Patched command lifecycle tracking to prevent dropped activity states during backgrounding.

## [1.0.4] - 2026-06-24

### Added

**Host Bulk Actions**
- Added "Recheck all services on host" and "ACK all services on host".
- Evaluates service targets at confirm-time to prevent stale pre-computed lists from missing services.
- Automatically excludes already-ACKed services from bulk targets.
- Added debug log tags (`[HOST_BULK_RECHECK]`, `[HOST_BULK_ACK]`, `[LOCAL_ACK]`).

**60-Second Local ACK Grace Period**
- Added a 60-second optimistic UI hold after an ACK command to stop cards from flickering back to "unacknowledged" while Nagios processes the request.
- Shows a `Warning` snackbar detailing unconfirmed services if the server fails to confirm within the window.

**Alert Detail Improvements**
- **Acknowledgement Section:** Surfaces status, author, timestamp, and a copyable comment.
- **Live Nagios Comments:** Fetches actual ACK comments directly from Nagios (`statusjson.cgi?query=commentlist`), supporting Nagios 4.x JSON shapes.
- **Instance Row:** Added the instance name to the Details section to identify sources in ALL-instances mode.

**Settings & UI**
- **About Page:** Added app icon and dynamically-tracked version number.

---

## [1.0.3] - 2026-06-08

### Added

**Smartwatch / wearable notification compatibility**
- Added a dedicated `nagios_alerts` notification channel (IMPORTANCE_HIGH, vibration, public
  lockscreen visibility) separate from the low-noise `Reliability service` foreground channel.
- In Reliability Mode the foreground service now posts a second notification through
  `nagios_alerts` on every poll cycle alongside the existing persistent status notification.
  The alert notification uses `setOngoing(false)`, `setLocalOnly(false)`,
  `setVisibility(VISIBILITY_PUBLIC)`, and a vibration pattern so Samsung Galaxy Wearable /
  Galaxy Fit 3 (and compatible wearables) can detect qNag as a notification source and
  forward alerts to the watch exactly as aNag does.
- Re-alert logic: the alert notification vibrates/alerts only when new problems appear or the
  worst severity worsens; repeat poll cycles with unchanged state update the notification
  silently via `setOnlyAlertOnce`.  On all-clear the notification is cancelled automatically.
- Added **"Send test alert notification"** button in Settings → Monitoring Health (Recovery
  actions).  Posting a test alert registers qNag in the Samsung Galaxy Wearable notification
  app list without waiting for a real Nagios problem.
- Renamed the foreground service notification channel display name from
  "qNag Monitoring Service" to "Reliability service" for clarity in Android notification
  settings.

**Reliability Checklist**
- After the first Nagios instance is added or imported, a Reliability Checklist screen is
  shown before the Dashboard so users can verify monitoring will work reliably on their device.
- Six checklist rows with colored status icons (OK / WARNING / ERROR):
  1. Notification permission — prompts for Android 13+ POST_NOTIFICATIONS if not granted.
  2. Battery optimization — detects whether qNag is exempt; provides a direct link to remove the restriction.
  3. Exact Alarm Watchdog permission — detects Android 12+ SCHEDULE_EXACT_ALARM; links to system settings.
  4. Reliability Mode — shows whether the foreground-service polling is enabled.
  5. Android alerts — shows whether qNag's notification toggle is enabled.
  6. Alert notification channel — shows whether the summary channel is configured and not muted.
- Status icons and system-level permission states refresh every 2 seconds while the checklist
  is visible so changes made in system settings are reflected immediately on return.
- "Start monitoring" button navigates to the Dashboard; all issues are informational — the
  user is never blocked from proceeding.
- Explanatory card describes Android power management limitations and directs users to
  Settings → Monitoring Health for ongoing reliability verification.
- Existing users who have already configured instances are never shown the Reliability Checklist.

**Alert sound defaults and auto-stop**
- qNag now uses a short bundled alert sound (`R.raw.qnag_alert`) by default instead of the
  system default alarm tone — prevents long or looping alarm tones from ringing indefinitely
  on fresh installs.
- Alert sounds now always stop automatically. New `maxAlertSoundSeconds` setting (default 10,
  range 1–60) caps all playback regardless of sound type — bundled, custom, or system fallback.
- New sound selection priority: (1) user-selected custom URI, (2) embedded qNag default sound,
  (3) system default alarm, (4) system default ringtone.  If the custom URI fails, qNag falls
  back to the embedded default rather than the system alarm.
- Custom URI playback now uses `MediaPlayer` for the embedded sound and enforces the same
  max-duration cap; looping alarm tones can no longer ring beyond the cap.
- In-app sound mode now calls `setSilent(true)` on the summary notification so the
  notification channel cannot produce a duplicate sound or vibration on the first post —
  previously only `setOnlyAlertOnce` was set, which did not cover fresh notification posts.
- Settings UI: "Alert sound duration (seconds)" field added under the sound picker.
- Settings UI: label for the default sound (no custom URI set) changed from "Default alarm"
  to "qNag default alert".

**Default reliability settings**
- `notificationsEnabled` now defaults to `true` (was `false`) — new installs enable Android alerts
  automatically; no manual toggle required before the first alert fires.
- `keepMonitoringActive` (Reliability Mode) now defaults to `true` (was `false`) — new installs
  use foreground-service polling from the first launch.
- Startup notification permission prompt is now guarded — the system prompt is no longer shown
  before any instance is configured, preventing a confusing permission dialog on fresh install.
- `SecureInstanceStore` JSON parsing fallbacks for both fields updated to `true` to match the
  new defaults; users who explicitly stored `false` are unaffected (the stored value is preserved).

**Welcome screen**
- First-run experience: when the app starts with no configured instances it shows a Welcome
  screen instead of jumping straight into the Add Instance form.
- The Welcome screen shows the app name, a one-line description, a brief feature summary,
  the current version number, and two buttons — "Add Nagios instance" and "Import configuration".
- "Import configuration" on the Welcome screen reuses the existing import flow (SAF file
  picker → preview dialog → merge) so new users can restore a qNagstamon export in one step.
- After a successful add or import the app navigates to the Reliability Checklist before the
  Dashboard.
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
