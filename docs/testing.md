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
