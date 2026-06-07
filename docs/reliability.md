# qNag Reliability Mode

> Before using qNag for on-call monitoring, validate Reliability Mode, notifications,
> sound, battery settings, exact alarm permission, and recovery behavior on your actual
> Android device and OS version.

---

## Overview

qNag uses multiple complementary layers to stay alive and alerting on Android. No single mechanism is guaranteed on all devices, so qNag layers them to maximize reliability and to make degraded states visible.

---

## Layer 1 — Foreground Service (Primary)

**When active:** Reliability Mode is ON (`Settings → Commands → Reliability Mode`).

**Behavior:**

- Android foreground service with a persistent status-bar notification.
- Polls all enabled + notifications-enabled Nagios instances on the configured interval, with a minimum of 30 seconds.
- Uses `START_STICKY` — Android attempts to restart the service if it is killed.
- When the service starts, WorkManager polling is canceled to avoid duplicate notifications.
- The persistent notification shows the current alert summary: title, per-instance counts, and visual state.

**Limitations:**

- Android 15+ (API 35) imposes a background runtime limit on `dataSync` foreground services.
- OEM battery killers, such as some Samsung/Xiaomi background policies, can still stop foreground services despite the persistent notification.
- Foreground services are more reliable than ordinary background work, but they are not guaranteed on every device/OS version.

---

## Layer 2 — Exact Alarm Watchdog

**When active:** Reliability Mode is ON and the Exact Alarm permission is granted (`SCHEDULE_EXACT_ALARM` on Android 12+).

**Behavior:**

- Schedules a repeating exact alarm via `AlarmManager.setExactAndAllowWhileIdle`.
- Default interval: 2 minutes, configurable in `Settings → Monitoring & Reliability`.
- On each alarm fire:
  - If the foreground service is healthy and recent: reschedule the alarm and exit.
  - If the service is dead or stale: attempt to restart it, enqueue a WorkManager one-shot check, and update stale/health state as applicable.
- Cancels itself when Reliability Mode is disabled.
- If exact alarm permission is not granted, qNag records the degraded state and relies on the available fallback path, such as WorkManager and any approximate alarm behavior supported by the current implementation/device.

**Why it exists:**

The foreground service can be killed by the OS before it can reschedule itself. The watchdog is an independent recovery mechanism that can fire even when the service is dead.

---

## Layer 3 — WorkManager (Fallback)

**When active:**

- Reliability Mode is OFF, scheduled via `scheduleOrCancel`, OR
- The foreground service dies, scheduled via `scheduleFallback`, OR
- After device boot or package update, when the boot/update receiver reschedules fallback work.

**Behavior:**

- Periodic WorkManager task polls all enabled + notifications-enabled instances.
- Minimum interval: **15 minutes**. This is an Android system-enforced minimum for periodic WorkManager.
- Not suitable for high-frequency on-call monitoring.
- Uses `ExistingPeriodicWorkPolicy.KEEP` — does not restart if already enqueued.

**Important:** WorkManager is a fallback and safety net. It is not a replacement for Reliability Mode when high-frequency polling is required.

---

## Layer 4 — Monitoring Health

**Location:** `Settings → Monitoring & Reliability → Monitoring Health`.

**Shows:**

- Last poll started/finished timestamps.
- Last successful poll timestamp.
- Foreground service running/stopped state.
- WorkManager scheduled/overdue state.
- Exact Alarm Watchdog state.
- Notification permission and channel health.
- Battery optimization state.
- Recovery action buttons, such as notification settings, battery settings, restart Reliability Mode, and reschedule fallback/watchdog actions.

Use Monitoring Health to verify that all layers are functioning correctly before relying on qNag for on-call use.

---

## Layer 5 — In-App Alert Sound

**How it works:**

- qNag's own audio engine plays sounds independently of Android notification channel sound settings.
- It can be configured to use the alarm audio stream, which may allow sound even in vibration mode depending on Android/OEM/DND policy.
- Fingerprint-based deduplication: unchanged alerts do not re-play sound during the cooldown.
- Severity escalation: a new critical alert bypasses the global cooldown and sounds immediately.
- Sound state is persisted in SharedPreferences across process restarts.

---

## Layer 6 — Event Log

**Location:** `Settings → Event Log`.

**Purpose:**

- In-app log of polling, service, command, watchdog, notification, and sound events.
- Useful for diagnosing issues without requiring ADB/logcat.
- Safe to share: no passwords, cookies, Authorization headers, or credentialed URLs are logged.
- Keeps the last 500 entries.

---

## Android and OEM Caveats

- **Android Doze and App Standby** can delay or stop background execution. Unrestricted battery usage reduces but does not eliminate this risk.
- **OEM battery killers** can kill even foreground services on some devices.
- **Android 12+** may require the user to grant `SCHEDULE_EXACT_ALARM` ("Alarms & reminders") permission. Without it, qNag relies on its available fallback behavior.
- **Android 15+** introduced time limits on `dataSync` foreground services. qNag handles `onTimeout` by scheduling fallback and stopping gracefully.
- **WorkManager is not high-frequency.** The 15-minute minimum means there can be a substantial gap after the foreground service dies if the watchdog also fails.

---

## Recommended Setup for On-Call Use

1. Enable **Reliability Mode** (`Settings → Commands → Reliability Mode ON`).
2. Enable **notifications** and grant notification permission.
3. Set battery optimization to **Unrestricted** for qNag (`Settings → Apps → qNag → Battery → Unrestricted`).
4. Grant **Alarms & reminders** permission (`Settings → Apps → Special app access → Alarms & reminders`).
5. Configure and **test alert sound** (`Settings → Notifications & Sound → Alert sound`).
6. Verify **Monitoring Health** — all layers should be green/healthy with no warnings.
7. Check **Event Log** after a test period — polling events should appear every 30–60 seconds when Reliability Mode is active.
8. Perform at least one overnight screen-off test before relying on the device for on-call use.
9. Validate on your **actual device and Android version** before relying on qNag for critical alerts.

---

## Limitations and Honest Expectations

qNag is designed to be as reliable as possible on Android, but **Android and OEM restrictions can still interfere**. For critical monitoring environments, qNag should be one alerting path among others — alongside server-side alerting, email, PagerDuty, or a secondary phone.

The Monitoring Health screen and Event Log exist precisely to help you detect when qNag is not polling as expected, so you can take corrective action.
---

## Tier 2+ Notification Delay

**Location:** Settings → Notifications & Sound → Tier 2+ notification delay.

**Purpose:** Delays Android alerting/sound until an alert has persisted long enough to be
worth waking someone up. Transient alerts that resolve quickly are visible in the qNag
dashboard but do not trigger Android sound or notifications.

**Important:** Tier 2+ delays Android *alerting* only. Dashboard visibility is immediate and
unaffected — all alerts appear in the qNag problem list the moment they are detected.

**How it works:**
- When Tier 2+ is enabled, each alert's age is compared to the configured delay threshold.
- Age is derived from Nagios `last_state_change` when available, or from a local
  first-seen timestamp stored by qNag.
- Until the threshold is met, the alert is suppressed from Android notifications and sound.
- When the threshold is crossed, the alert becomes notification-eligible and sounds once
  (subject to existing cooldown rules).
- Cards show a **T2+** badge for alerts currently waiting on the delay.
- A banner "Tier 2+ active · notify after Xm" is shown on the dashboard.

**Per-state delays:** Enable "Use different delay per state" to configure separate thresholds
for host DOWN, UNREACHABLE, service CRITICAL, WARNING, and UNKNOWN.

**Interaction with other filters:**
- Downtime and soft-state suppression still apply after the Tier 2+ delay is satisfied.
- ACKed alerts remain quiet by default even after the Tier 2+ threshold is met.
- ACKed alert re-notification is a separate setting.

## ACKed Alert Re-Notification

**Location:** Settings → Notifications & Sound → ACKed alert re-notification.

**Purpose:** Alerts that have been acknowledged are normally quiet. This option re-enables
notification/sound after an ACKed alert has remained active for a configurable duration —
useful when an ACK is no longer relevant or has been forgotten.

**How it works:**
- Tracks how long each ACKed alert has been acknowledged, using Nagios's
  `acknowledgement_time` field when available, or a local timestamp otherwise.
- When the configured duration is exceeded, the alert becomes eligible for one
  re-notification (subject to existing fingerprint/cooldown logic).
- Does not trigger sound on every poll — only on the first crossing of the threshold.
