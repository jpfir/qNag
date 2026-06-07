# qNag

<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

A production-minded, FOSS Android Nagios monitoring client inspired by Qnagstamon,
built with Kotlin, Jetpack Compose, and Material3.

---

## Status

**Under active development — field-tested, not yet formally released.**

qNag is a production-minded monitoring client currently being validated against real Nagios
installations. It has a complete feature set including multi-instance support, Reliability Mode,
in-app sound, downtime scheduling, and an Event Log for troubleshooting.

> **Before using qNag for on-call monitoring, validate Reliability Mode, notifications, sound,
> battery settings, exact alarm permission, and recovery behavior on your actual Android device
> and OS version. See [docs/testing.md](docs/testing.md).**

---

## Features

- **Multi-instance** — configure, enable, and monitor multiple Nagios instances simultaneously.
- **ALL-instances dashboard** — merged sorted view across all enabled instances.
- **Summary chips and quick filters** — tap D/U/C/W/N chips to filter the problem list by state.
- **ACK / Recheck / Remove ACK** — with host ACK cascade to related services.
- **Downtime scheduling** — service, host, or host + all services; fixed duration.
- **Multi-select actions** — bulk ACK, recheck, remove ACK, and downtime.
- **Problem details** — full check timing, passive/freshness metadata, open in Nagios link.
- **Reliability Mode** — foreground service + Exact Alarm Watchdog + WorkManager fallback.
- **One notification in foreground mode** — Reliability Mode normally keeps a single visible qNag status notification in the shade.
- **Advanced notification channels** — additional channels exist for compatibility, fallback modes, and optional per-problem notifications.
- **In-app alert sound** — independent of Android channel sound settings; can be configured to sound even when the phone is in vibration mode, subject to Android/OEM audio and DND policy.
- **Monitoring Health** — live status of all reliability layers with recovery actions.
- **Event Log** — in-app log of polling, commands, and reliability events; safe to share.
- **Filters** — hide acknowledged, downtime, soft-state, disabled-notification, and regex filters.

---

## Reliability Mode

qNag uses four complementary layers to stay alive and alerting on Android.

| Layer | When active | Minimum interval |
|---|---|---|
| **Foreground service** | Reliability Mode ON | 30 s (configurable) |
| **Exact Alarm Watchdog** | Reliability Mode ON + exact alarm permission | 1–15 min (configurable) |
| **WorkManager** | Reliability Mode OFF, or as fallback when service dies | 15 min (Android minimum) |
| **Boot/update receiver** | After device boot or package update | — |

**WorkManager is not high-frequency.** It is a fallback, not the primary polling mechanism.

See [docs/reliability.md](docs/reliability.md) for full details, recommended setup, and known Android/OEM caveats.

---

## One-Notification Design

In Reliability Mode, qNag shows **one** useful notification in the Android shade:

> **qNag: 3 critical, 7 warning**
> 2 instances · checking every 30s
> *(expand)* EvoExads: C3·W7; Exoclick: OK

- The notification title reflects the current worst alert state.
- The notification large icon is colored: green (OK), amber (warning), red (critical/down), purple (unknown), orange (failure/stale).
- Sound is produced by qNag's own in-app audio engine, independent of Android channel settings.

---

## Notification Channels

| Channel | Purpose | Sound |
|---|---|---|
| qNag Monitoring Service | Quiet foreground notification | None |
| qNag Alert Summary | Audible summary (WorkManager/background mode) | Yes (configurable) |
| qNag: Monitoring Stale | Stale-poll self-alert (WorkManager mode) | Default |
| qNag: Unable to connect | Per-instance fetch failure (PER_PROBLEM mode) | Low |
| qNag: Host DOWN / CRITICAL / etc. | Per-problem mode only | High |

---

## Building

Clone the repository and open it in Android Studio, or build from the command line:

```bash
./gradlew assembleDebug
```

You need a standard Android development environment with Android SDK installed.
Do not commit `local.properties` — it contains your local SDK path.

### Signed Release APK

Release APKs are built by the GitHub Actions workflow on `v*` tags.
See [.github/workflows/android-release.yml](.github/workflows/android-release.yml) for
the required secrets (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`).

Debug builds work without any signing secrets.

---

## Configuration

In the app, add Nagios instances with:

- Display name, URL, username, password.
- **Monitoring** toggle — instance is queried and shown in the dashboard.
- **Android alerts** toggle — instance contributes to Android shade status and per-alert notifications.

Credentials are stored using Android encrypted shared preferences backed by Android Keystore.

---

## Documentation

| Document | Purpose |
|---|---|
| [docs/testing.md](docs/testing.md) | Production-readiness regression checklist |
| [docs/reliability.md](docs/reliability.md) | Reliability Mode architecture, setup, and caveats |
| [docs/nagios-compatibility.md](docs/nagios-compatibility.md) | Nagios CGI compatibility and configuration notes |
| [CHANGELOG.md](CHANGELOG.md) | Feature history |

---

## Privacy and Security

- Passwords and Authorization headers are never logged.
- Stored credentials are encrypted on-device using Android encrypted preferences backed by Android Keystore.
- Credentialed URLs are sanitized in all error messages and logs.
- The Event Log is designed to be safe to share with developers (no credentials).
- On-device storage: encrypted instance credentials (Android Keystore), plain settings and polling state (SharedPreferences).

---

## Nagios Compatibility

qNag uses `statusjson.cgi` and `cmd.cgi`. It handles CSRF/NagFormId cookie two-step command
submission. See [docs/nagios-compatibility.md](docs/nagios-compatibility.md) for details on
supported operations, command permissions, date format configuration, and reverse proxy notes.

---

## Project Layout

```text
qNag/
├── app/
│   └── src/main/java/com/exogroup/qnag/
│       ├── MainActivity.kt
│       ├── data/            — models, API, settings, storage, EventLog
│       ├── notifications/   — NotificationHelper, NotificationIconHelper
│       ├── reliability/     — ExactAlarmWatchdogScheduler/Receiver
│       ├── service/         — NagiosMonitoringService (foreground)
│       ├── sound/           — AlertSoundController, AlertSoundPlayer
│       ├── ui/              — Compose screens and components
│       ├── viewmodel/       — NagiosViewModel
│       └── worker/          — BackgroundPollingScheduler, NagiosPollingWorker
├── docs/
├── .github/workflows/
├── CHANGELOG.md
└── LICENSE
```

### Quick "where do I edit this?" guide

| Task | File(s) |
|---|---|
| Nagios status parsing | `data/NagiosApi.kt` |
| ACK/recheck/downtime behavior | `data/NagiosApi.kt`, `viewmodel/NagiosViewModel.kt` |
| Foreground polling | `service/NagiosMonitoringService.kt`, `MainActivity.kt` |
| WorkManager polling | `worker/BackgroundPollingScheduler.kt`, `worker/NagiosPollingWorker.kt` |
| Watchdog | `reliability/ExactAlarmWatchdogScheduler.kt`, `ExactAlarmWatchdogReceiver.kt` |
| Dashboard UI | `ui/DashboardScreen.kt`, `ui/ProblemCard.kt`, `ui/InstanceSummaryPanel.kt` |
| Settings UI | `ui/SettingsScreen.kt` and `ui/*SettingsSection.kt` |
| Notification logic | `notifications/NotificationHelper.kt` |
| Alert sound | `sound/AlertSoundController.kt`, `sound/AlertSoundPlayer.kt` |
| Event Log | `data/EventLog.kt`, `ui/EventLogScreen.kt` |
| Monitoring Health | `data/MonitoringHealth.kt` |
| Stored settings | `data/AppSettings.kt`, `data/SecureInstanceStore.kt` |

---

## Tech Stack

- Kotlin + Jetpack Compose + Material 3
- OkHttp (Nagios HTTP client)
- Gson (JSON parsing)
- AndroidX Security Crypto (encrypted preferences)
- WorkManager (background polling fallback)
- AlarmManager (`setExactAndAllowWhileIdle` watchdog)
- Foreground Service (reliability mode)

---

## Known Limitations

- Android/OEM battery killers can stop foreground services despite the persistent notification.
- WorkManager periodic interval is minimum 15 minutes — not suitable as the sole high-frequency polling mechanism.
- Android 15+ imposes runtime limits on `dataSync` foreground services; qNag handles `onTimeout` gracefully.
- Nagios XI REST API is not used; qNag uses the CGI layer only.
- LDAP/SAML/OAuth auth in front of Nagios CGI is not supported.

---

## Disclaimer

qNag is designed for serious monitoring use, but Android background execution varies by device, OS version, and vendor policy. Validate it on your own device before depending on it for on-call alerting.
Use it at your own risk. If it wakes you up at 3 AM for the wrong reason, I am sorry.
If it does not wake you up at 3 AM when it should, I am even more sorry.

For reliability details and recommended setup, read [docs/reliability.md](docs/reliability.md).

qNag is related to qNagstamon and shares some ideas, but it is a separate Android project.

---

## License

SPDX-License-Identifier: GPL-3.0-or-later

qNag is licensed under the GNU General Public License v3.0 or later.

See [LICENSE](LICENSE) for the full license text.
