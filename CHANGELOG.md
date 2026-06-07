# Changelog

All notable changes to qNag are documented here.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning uses [Semantic Versioning](https://semver.org/).

SPDX-License-Identifier: GPL-3.0-or-later

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