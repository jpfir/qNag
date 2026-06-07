# qNag Testing

## Automated JVM Unit Tests

Run with `./gradlew test` (no device required, runs in CI on every PR).

| Test class | What is covered |
|---|---|
| `NotificationFilterTest` | Notification decisions: state enabled/disabled, soft-state, downtime, ACK suppression, ACK re-notification after threshold, Tier 2+ delay before/after threshold, per-state Tier 2+ thresholds, decision ordering |
| `FingerprintTest` | `problemFingerprint` U+001F separator, `instanceFingerprintPrefix` scoping, `notificationId` stability across status changes, `fetchFailureNotificationId` uniqueness, `AckAgeStore`/`ProblemAgeStore` key contracts |
| `FilterSettingsTest` | `applyFilters` quick filters (C/W/D equivalent), all status/state/host-context filters, regex normal and reversed, invalid-regex safe handling, `validateRegex` |
| `VisualStateTest` | `deriveVisualState` priority order (CRITICAL > FETCH_FAILURE > WARNING > UNKNOWN > OK), `visualStateColor` uniqueness and dominant-channel checks |
| `AlertSortingTest` | `severityRank` values, sort order: severity, recency, acked/downtime/notification-enabled, alphabetic tiebreaker |
| `NagiosTimestampTest` | `parseEpochMs`: null/zero/negative → null; 10-digit seconds → millis; 13-digit millis → unchanged; invalid strings → null |
| `EventLogSanitizeTest` | `EventLog.sanitize`: credentialed URL redaction (http/https), multiple-URL redaction, safe-content preservation (instance name, host/service, HTTP status, command type), 500-char truncation |
| `QNagSmokeTest` | End-to-end: critical service allowed, warning service suppressed by default |

The automated tests cover pure/business logic that does not require an Android device.
They will **fail** if Tier 2+ delay logic, ACK re-notification, fingerprint separators,
visual-state priority, sort order, timestamp parsing, or Event Log sanitization regress.

### What is NOT covered by automated tests

#### Requires an Android Context (Robolectric not added)

- **`ProblemAgeStore` / `AckAgeStore` write-through** — the SharedPreferences record-if-absent
  and getFirstSeen paths are not exercised by JVM tests.  `NotificationFilterTest` injects
  first-seen timestamps via the `testProblemFirstSeenFn` / `testAckFirstSeenFn` parameters
  to cover the decision logic, but the store's own read/write correctness is untested.
- **`ProblemAgeStore.pruneStaleKeys()` multi-instance safety** — a past bug caused pruning
  with only one instance's active keys to delete another instance's stored first-seen entries.
  This regression can only be reproduced with real SharedPreferences.  Until Robolectric is
  added, validate manually after changing any pruning logic:
  1. Add two Nagios instances with distinct problems.
  2. Leave both running for > 30 min so Tier 2+ first-seen entries accumulate.
  3. Remove one instance.
  4. Verify that the surviving instance's Tier 2+ badges still appear correctly after the
     next poll (pruning must not have deleted its keys).
- **`AckAgeStore.recordIfAbsent()` persistence** — ACK re-notification relies on the first-seen
  timestamp surviving across app restarts; this is only guaranteed by testing with real prefs.

#### Requires a real Android device / OEM validation

- Exact alarm behavior, WorkManager scheduling, foreground service lifecycle
- Notification permission, channel, DND, vibrate-mode, battery-optimization behavior
- In-app sound playback and alarm audio stream routing
- Nagios command submission (ACK/recheck/downtime) against a live Nagios instance
- OEM-specific background restrictions and process survival

See the manual checklist below for device validation steps.

---

# qNag Production Readiness Test Checklist

> Before using qNag for on-call monitoring, validate Reliability Mode, notifications,
> sound, battery settings, exact alarm permission, and recovery behavior on your actual
> Android device and OS version.

---

## 1. Startup and Instances

- [ ] First launch — Add Instance screen appears, no crash
- [ ] Add instance (name, URL, username, password) — saved and dashboard appears
- [ ] Edit instance — URL/name change takes effect immediately
- [ ] Disable instance — removed from polling; dashboard shows remaining instances
- [ ] Enable instance — polling resumes
- [ ] Remove instance — confirmation shown; instance gone from all screens
- [ ] ALL selector persistence — re-open app, ALL mode remembered
- [ ] Summary expanded/collapsed persistence — collapse, reopen app, still collapsed

---

## 2. Dashboard

- [ ] Single-instance mode — problems shown correctly for the selected instance
- [ ] ALL mode — problems from all enabled instances merged and sorted
- [ ] Summary chips (D/U/C/W/N) — show correct counts
- [ ] Quick filter: tap C chip — only critical services shown; banner says "Showing: Critical services"
- [ ] Quick filter: tap W chip — only warning services shown
- [ ] Quick filter: tap D chip — only host DOWN shown
- [ ] Active quick filter clear — tap same chip again or press Clear; full list restored
- [ ] Section headers — "CRITICAL SERVICES · N", "HOST DOWN · N", etc. visible
- [ ] Sort order — within same severity: newest first, unacked before acked
- [ ] "Severity first · newest within state" label visible below count
- [ ] Pull-to-refresh — spinner appears; list updates
- [ ] Manual refresh (refresh icon) — list updates
- [ ] Details screen — tap card overflow → Details; full metadata shown; back returns to correct instance scope
- [ ] Card overflow actions — ACK, Recheck, Remove ACK, Schedule downtime, Copy, Share
- [ ] Multi-select mode — long press enters selection; ACK/Recheck/Remove ACK/Downtime work; Clear selection

---

## 3. Nagios Commands

- [ ] ACK service — problem gains ACK badge; badge disappears after next poll confirms
- [ ] ACK host — host problem gains ACK badge
- [ ] ACK host + related services (`ackServicesOnHostAck`) — cascade ACK applied to services on same host
- [ ] Remove ACK — ACK badge removed; problem visible again if hide-acked filter is on
- [ ] Bulk ACK — multi-select several problems → Acknowledge; all get ACK badge
- [ ] Recheck service — "Recheck pending" chip shown; badge clears after Nagios executes
- [ ] Bulk recheck — multi-select → Recheck; all show pending
- [ ] Downtime service — dialog appears; set duration/scope/comment; submitted; instance refreshes
- [ ] Downtime host — host only scope works
- [ ] Downtime host + services — "Schedule (host + services)" label visible; explicit warning shown
- [ ] Multi-select downtime — mixed selection shows "Services get service DT; Host alerts get host+services DT"
- [ ] ALL mode command routing — each command goes to the correct instance, including same hostname on two different instances
- [ ] Duplicate command prevention — rapid double-tap does not submit twice
- [ ] Failed command Event Log entry — safe error shown, no HTML dump with cookies/auth data

---

## 4. Reliability Mode

- [ ] Enable Reliability Mode — foreground service starts, persistent notification appears
- [ ] Foreground polling at 30s — poll every ~30 seconds (check Event Log)
- [ ] Foreground polling at 60s — change interval, verify Event Log shows correct interval
- [ ] Exact Alarm Watchdog — appears in Monitoring Health; fires every 2 min (configurable)
- [ ] Exact alarm permission denied while Reliability Mode is ON — watchdog warning shown; fallback remains available
- [ ] WorkManager fallback — disable Reliability Mode; WorkManager shows as scheduled in Monitoring Health
- [ ] App process killed by OS — WorkManager fallback eventually runs or watchdog recovers the service
- [ ] Manual force-stop from Android settings — verify qNag shows degraded state after reopening
- [ ] Foreground service killed — Monitoring Health detects stale; watchdog attempts recovery
- [ ] App reopened — if Reliability Mode still ON, service restarts on resume
- [ ] Stale monitoring alert — Monitoring Health shows stale if no successful poll within threshold
- [ ] Watchdog recovery — service stopped, watchdog fires, service restarted
- [ ] WorkManager overdue state — Monitoring Health shows warning when WorkManager is behind and fallback is expected
- [ ] Event Log — service start/stop, poll success/failure events visible in `Settings → Event Log`
- [ ] Overnight screen-off test — foreground service continues polling or degraded state/recovery is visible in Monitoring Health/Event Log

---

## 5. Notifications and Sound

- [ ] All green — notification disappears or says "all green"; sound does not play
- [ ] Warning state — amber notification icon; no sound during cooldown
- [ ] Critical/host down — red notification icon; sound plays on first new/worse state
- [ ] New critical after warning — sound plays even within cooldown because severity escalation bypasses normal cooldown
- [ ] Unchanged alerts — do not repeat sound during cooldown period
- [ ] Fetch failure — orange notification icon; "N instances failed" in notification
- [ ] Stale monitoring (WorkManager only) — stale notification appears when no poll for stale threshold
- [ ] Foreground mode: single notification — only one qNag notification in shade during Reliability Mode
- [ ] Foreground notification visual state — warning/critical states do not remain green
- [ ] WorkManager mode: separate alert-summary notification when not in Reliability Mode
- [ ] Notification visual state — amber for warning, red for critical, green for all clear, purple for unknown, orange for failure/stale
- [ ] Notification large icon — colored circle matches visual state
- [ ] Notification tap — opens qNag dashboard
- [ ] Sound test — Settings → Notifications → sound picker → test; sound plays in-app
- [ ] Stop sound — active in-app alert sound stops immediately

---

## 6. Android Permissions and Settings

- [ ] Notification permission denied — permission prompt appears when enabling notifications
- [ ] App notifications fully disabled (Android system settings) — Monitoring Health shows warning
- [ ] Alert channel disabled — Monitoring Health shows channel warning
- [ ] Battery optimization restricted — Monitoring Health shows battery warning and "Fix" button
- [ ] Exact alarm permission missing — Monitoring Health shows watchdog warning; fallback remains available
- [ ] Exact alarm permission granted — watchdog uses `setExactAndAllowWhileIdle`
- [ ] DND mode — alert sound behavior is tested with the configured audio stream and the device's actual DND policy
- [ ] Vibration mode — alert sound behavior is tested with the configured audio stream and actual device policy

---

## 7. Security Sanity

- [ ] Event Log — no passwords, cookies, Authorization headers, or credentialed URLs in any entry
- [ ] Event Log share — shared text contains only instance names, counts, safe error messages
- [ ] Logcat debug output — no passwords or credentialed URLs when `debugCommandSubmission = true`
- [ ] Copy/share alert text — no credentials in copied output or share text
- [ ] Instance edit — existing password is never displayed in the edit dialog
- [ ] Command submissions — debug log shows field names, not credential values
- [ ] Failed HTTP / command errors — no raw HTML with cookies/auth data is copied into the Event Log or shared output

---

## Notes

- Test on real Android hardware. Emulators do not accurately represent battery/alarm behavior.
- Test on your specific Android version and OEM. Some devices have aggressive battery/background policies.
- Validate that the foreground service survives overnight with the screen off.
- Manual force-stop is a special Android state: the OS may prevent alarms/jobs/receivers from running until the user opens the app again.
- Check Event Log after each test session for unexpected errors or gaps in polling.

---

## 8. Tier 2+ Notification Delay

- [ ] Tier 2+ disabled (default) — alert sounds immediately when detected
- [ ] Enable Tier 2+ with 5m delay — alert visible in dashboard immediately, no sound for 5m
- [ ] After 5m — alert becomes notification-eligible; sound plays once
- [ ] T2+ badge visible on card while waiting; badge disappears after threshold
- [ ] "Tier 2+ active · notify after 5m" banner visible on dashboard
- [ ] Per-state delays enabled — set different delays per state and verify each
- [ ] Critical vs Warning — verify critical sounds after its configured delay, warning after its own
- [ ] Downtime with Tier 2+ — problem in downtime does not sound even after Tier 2+ threshold
- [ ] Soft state with notifyOnlyHardState=true — does not sound even after Tier 2+ threshold
- [ ] ACKed alert with Tier 2+ — remains quiet even when Tier 2+ delay is satisfied
- [ ] Event Log — "Tier 2+ delay" entry appears for new waiting alerts; "Tier 2+ eligible" when threshold crossed; no spam per poll
- [ ] Disable Tier 2+ while alerts are present — sound fires immediately on next poll
- [ ] Restart app with Tier 2+ active — waiting state persists (first-seen stored in ProblemAgeStore)

## 9. ACKed Alert Re-Notification

- [ ] notifyAckedAfterEnabled=false (default) — ACKed alerts remain quiet indefinitely
- [ ] Enable re-notify after 60m — ACKed alert remains quiet for 60m, then sounds once
- [ ] Re-notify fires only once — no repeated sound after re-notify threshold crossed
- [ ] ACK removed and re-applied — timer resets (new ACK timestamp)
- [ ] Event Log — "ACKed re-notify eligible" entry when threshold is crossed
