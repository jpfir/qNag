# qNag

Another boring Nagios client for Android.

qNag is an experimental Android client for monitoring Nagios instances. It is inspired by tools like aNag, and Qnagstamon, but with some mobile-focused ideas I wanted to test.

This project is also my excuse to learn Kotlin. I still hate Java, so if parts of this code suck, I am not yet sure if that is because I am bad at Java/Kotlin, because Java itself is cursed, or because this was vibe-coded and then mostly reviewed by a tired human.

Probably all three.

## Status

**Experimental / prototype / use at your own risk.**

The app is currently being built and tested against real Nagios installations, but it is not yet a polished production-grade monitoring client.

Expect bugs, weird UI decisions, missing edge cases, and occasional "why did Android do that?" moments.

## What it does

qNag can currently:

- Store one or more Nagios instances.
- Enable, disable, edit, and remove instances.
- Show host and service problems.
- Show all instances together or one instance at a time.
- Filter alerts.
- Acknowledge alerts.
- Submit service and host rechecks.
- Show ACK state in the UI.
- Poll in the foreground for more frequent checks.
- Use WorkManager as a background fallback.
- Send Android notifications.
- Avoid some duplicate notification and command spam.
- Store settings locally.

## What it wants to become

The idea is to have a lightweight Android Nagios client with some ideas borrowed from Qnagstamon:

- better multi-instance handling,
- useful filters,
- sane notifications,
- clear ACK/recheck actions,
- foreground monitoring mode,
- eventually maybe maps/topology ideas,
- and a UI that is good enough to use during real incidents.

## Screenshots

TODO: add screenshots.

## Tech stack

- Kotlin
- Jetpack Compose
- Material 3
- OkHttp
- Gson
- AndroidX Security Crypto
- WorkManager
- Foreground Service for more frequent polling

## Building

Clone the repository and open it with Android Studio.

Or build from the project root:

```bash
./gradlew assembleDebug
```

You need a normal Android development environment with the Android SDK installed.

Do not commit `local.properties`; it is machine-specific and usually contains your local Android SDK path.

## Configuration

Inside the app you can add Nagios instances with:

- display name,
- URL,
- username,
- password,
- enabled/disabled state,
- notification enabled/disabled state.

Credentials are stored locally using Android encrypted preferences. This is good enough for early testing, but the security model still deserves a proper review before anyone should blindly trust it.

## Nagios compatibility notes

qNag talks to Nagios CGI endpoints such as:

- `statusjson.cgi`
- `cmd.cgi`

ACK and recheck support depends on the Nagios CGI configuration and the permissions of the configured user.

The app attempts to handle Nagios CSRF form IDs/cookies when submitting commands, but different Nagios versions, reverse proxies, and authentication setups may behave slightly differently.

## Android background monitoring notes

Android is aggressive about background work.

qNag has two polling modes.

### WorkManager mode

This is the safer Android-native background option, but Android imposes timing limits. It is not intended for exact high-frequency polling.

### Foreground service mode

This is more suitable for frequent polling because the app runs with a persistent notification.

However, even foreground services are not magic. Android may still limit, stop, or delay work depending on OS version, battery settings, vendor restrictions, and device state.

If you need absolutely reliable alerting, server-side push notifications or a proper paging system are still better than relying only on phone-side polling.

## Project layout for tired humans

Android projects are a bit of a PITA, so here is the short version of what lives where.

```text
qNag/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
└── app/
    ├── build.gradle.kts
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/com/exogroup/qnag/
        │   └── res/
        ├── test/
        └── androidTest/
```

### Gradle files

Gradle is the Android build system. Most of the boring project wiring is here.

| File | Purpose |
|---|---|
| `settings.gradle.kts` | Declares the project name and includes the `:app` module. Also configures plugin and dependency repositories. |
| `build.gradle.kts` | Root build file. Defines plugins shared by modules, usually with `apply false`. |
| `app/build.gradle.kts` | Main app build configuration: Android SDK versions, app id, Compose enablement, dependencies. |
| `gradle/libs.versions.toml` | Version catalog. Defines dependency and plugin versions used by aliases like `libs.androidx.compose.material3`. |
| `gradle.properties` | Gradle/Android build flags. |
| `gradlew`, `gradlew.bat` | Gradle wrapper scripts. Use these instead of a system Gradle install. |

To build:

```bash
./gradlew assembleDebug
```

If dependency versions need changing, look first in:

```text
gradle/libs.versions.toml
```

If app dependencies need adding, look in:

```text
app/build.gradle.kts
```

### Android manifest

```text
app/src/main/AndroidManifest.xml
```

This declares the Android app itself:

- app package/activity entry point,
- launcher icon,
- permissions like Internet, notifications, and foreground service,
- the foreground monitoring service.

If Android complains about missing permissions, services, launcher activity, or notification-related declarations, check here first.

### Kotlin source code

Most real code lives here:

```text
app/src/main/java/com/exogroup/qnag/
```

Yes, the folder is called `java`, even though the code is Kotlin. Android does that because history happened.

Current package layout:

```text
com/exogroup/qnag/
├── MainActivity.kt
├── data/
├── notifications/
├── service/
├── ui/
├── viewmodel/
└── worker/
```

### Main app entry point

```text
MainActivity.kt
```

This is the Android entry point. It wires together:

- app state,
- navigation between dashboard, settings, and add-instance screens,
- notification permission requests,
- foreground service start/stop decisions,
- WorkManager scheduling decisions.

If something happens "when the app starts", "when settings change", or "when instances change", this file is probably involved.

### Data layer

```text
data/
```

This folder contains models, settings, storage, filtering, and Nagios API logic.

| File | Purpose |
|---|---|
| `NagiosModels.kt` | Core models: `NagiosInstance`, `NagiosProblem`, service/host problem types, Nagios status constants. |
| `NagiosApi.kt` | Talks to Nagios: fetches problems from `statusjson.cgi`, sends ACK/recheck commands to `cmd.cgi`. |
| `InstanceStore.kt` | Storage interface for instances/settings. |
| `SecureInstanceStore.kt` | Actual encrypted local storage implementation using Android encrypted preferences + Gson. |
| `AppSettings.kt` | Top-level app settings container. |
| `CommandSettings.kt` | ACK defaults, foreground polling options, debug command options. |
| `NotificationSettings.kt` | Notification behavior: enabled states, refresh interval, hard/soft/downtime rules. |
| `FilterSettings.kt` | Dashboard filter settings and `applyFilters()`. |
| `NotificationFilter.kt` | Decides whether a problem should notify, creates notification fingerprints/IDs. |
| `NagiosDateFormat.kt` | Supported Nagios date formats for scheduled check/recheck commands. |

Common changes:

| Want to change... | Start here |
|---|---|
| How problems are fetched | `data/NagiosApi.kt` |
| ACK/recheck command behavior | `data/NagiosApi.kt` + `viewmodel/NagiosViewModel.kt` |
| Stored settings | `data/AppSettings.kt`, `CommandSettings.kt`, `NotificationSettings.kt`, `SecureInstanceStore.kt` |
| Filtering logic | `data/FilterSettings.kt` |
| Notification eligibility | `data/NotificationFilter.kt` |

### ViewModel

```text
viewmodel/NagiosViewModel.kt
```

This is the bridge between UI and data/API logic.

It owns dashboard state like:

- loading/success/error,
- current problem lists,
- command state,
- ACK/recheck actions,
- local ACK overlay,
- refresh calls.

If the UI needs to "do something" but the code should not live directly inside a Composable, it probably belongs here.

### UI layer

```text
ui/
```

This folder contains Jetpack Compose screens and components.

| File | Purpose |
|---|---|
| `DashboardScreen.kt` | Main monitoring screen. Instance selector, refresh, problem list, ACK/recheck actions. |
| `ProblemCard.kt` | Individual host/service alert card. Swipe actions, colors, ACK badge, status badge. |
| `SettingsScreen.kt` | Main settings screen container. |
| `InstanceSettingsSection.kt` | Add/edit/remove/enable/disable Nagios instances. |
| `NotificationSettingsSection.kt` | Notification options. |
| `CommandSettingsSection.kt` | ACK defaults, command/debug options, foreground polling options. |
| `FilterSettingsSection.kt` | Dashboard filters and regex filters. |
| `AddInstanceScreen.kt` | First-run / add instance UI. |
| `theme/` | Compose theme colors, typography, Material theme wrapper. |

Common changes:

| Want to change... | Start here |
|---|---|
| Main dashboard layout | `ui/DashboardScreen.kt` |
| Alert card appearance | `ui/ProblemCard.kt` |
| Settings UI | `ui/SettingsScreen.kt` and section files |
| Instance editing UI | `ui/InstanceSettingsSection.kt` |
| App colors/theme | `ui/theme/` |

### Notifications

```text
notifications/NotificationHelper.kt
```

Handles Android notification channels and alert notifications.

This includes:

- notification channel creation,
- problem notifications,
- fetch failure notifications,
- foreground monitoring notification helpers,
- notification permission checks.

If sounds, notification grouping, channels, or alert titles need work, start here.

### Foreground monitoring service

```text
service/NagiosMonitoringService.kt
```

This is the optional foreground service used for more frequent polling.

It is used when the user enables "keep monitoring active" / foreground polling.

It should:

- show a persistent notification,
- poll enabled + notification-enabled instances,
- respect the foreground polling interval,
- avoid running at the same time as WorkManager.

If 30-second / 60-second polling is not working, check here and `MainActivity.kt`.

### Background polling

```text
worker/
├── BackgroundPollingScheduler.kt
└── NagiosPollingWorker.kt
```

This is the WorkManager-based background fallback.

| File | Purpose |
|---|---|
| `BackgroundPollingScheduler.kt` | Schedules or cancels periodic background polling. |
| `NagiosPollingWorker.kt` | Runs background checks and sends notifications. |

Important: WorkManager is Android-friendly, but it is not high-frequency. Periodic WorkManager has a practical minimum interval of 15 minutes. For faster polling, use the foreground service mode.

### Android resources

```text
app/src/main/res/
```

This contains Android XML resources.

Important folders:

| Folder | Purpose |
|---|---|
| `res/drawable/` | Vector drawables, launcher foreground/background XML. |
| `res/mipmap-*` | Launcher icons for different screen densities. |
| `res/mipmap-anydpi-v26/` | Adaptive launcher icon definitions. |
| `res/values/` | Strings, colors, themes. |
| `res/xml/` | Backup/data extraction rules. |

Common files:

```text
res/values/strings.xml
res/values/colors.xml
res/values/themes.xml
res/drawable/ic_launcher_background.xml
res/drawable/ic_launcher_foreground.xml
res/mipmap-anydpi-v26/ic_launcher.xml
res/mipmap-anydpi-v26/ic_launcher_round.xml
```

If the app icon is wrong, check `AndroidManifest.xml` plus the `mipmap` and `drawable` icon resources.

### Tests

```text
app/src/test/
app/src/androidTest/
```

| Folder | Purpose |
|---|---|
| `app/src/test/` | Local JVM tests. |
| `app/src/androidTest/` | Instrumented Android/device tests. |

Right now these are mostly placeholder/example tests.

### Files you usually should not commit

Do not commit local/generated/secret files such as:

```text
local.properties
.gradle/
build/
app/build/
*.jks
*.keystore
*.p12
*.pem
*.key
```

`local.properties` usually contains your local Android SDK path and is machine-specific.

### Quick "where do I edit this?" guide

| Task | File(s) |
|---|---|
| Add/edit Nagios instance fields | `data/NagiosModels.kt`, `ui/InstanceSettingsSection.kt`, `data/SecureInstanceStore.kt` |
| Change Nagios status parsing | `data/NagiosApi.kt` |
| Change ACK/recheck behavior | `data/NagiosApi.kt`, `viewmodel/NagiosViewModel.kt`, `ui/ProblemCard.kt` |
| Fix duplicate command submissions | `ui/ProblemCard.kt`, `viewmodel/NagiosViewModel.kt` |
| Change dashboard filters | `data/FilterSettings.kt`, `ui/FilterSettingsSection.kt` |
| Change notification rules | `data/NotificationSettings.kt`, `data/NotificationFilter.kt`, `notifications/NotificationHelper.kt` |
| Change foreground polling | `service/NagiosMonitoringService.kt`, `MainActivity.kt`, `data/CommandSettings.kt` |
| Change WorkManager polling | `worker/BackgroundPollingScheduler.kt`, `worker/NagiosPollingWorker.kt` |
| Change dashboard UI | `ui/DashboardScreen.kt`, `ui/ProblemCard.kt` |
| Change settings UI | `ui/SettingsScreen.kt` and `ui/*SettingsSection.kt` |
| Change app icon | `res/drawable/`, `res/mipmap-*`, `AndroidManifest.xml` |
| Add dependencies | `app/build.gradle.kts`, maybe `gradle/libs.versions.toml` |
| Change app id / SDK versions | `app/build.gradle.kts` |

## Known limitations

- The app is still experimental.
- UI/UX is evolving.
- Notification channels and sounds are still being refined.
- Foreground polling behavior depends on Android version and vendor battery policy.
- Nagios command submission may need tuning for different Nagios/Core/CGI setups.
- The code was vibe-coded, reviewed, patched, and reviewed again. That means some parts are surprisingly decent and others may be haunted.

## Roadmap

Possible future work:

- Better notification sound/channel configuration.
- Better anti-flood logic.
- More robust command diagnostics.
- Better instance editing and testing.
- Cleaner architecture.
- More complete Nagios compatibility.
- Import/export settings compatible with aNag and maybe Qnagstamon.
- Screenshots and proper release builds.
- Maybe topology/maps ideas inspired by Qnagstamon.

## Name

`qNag` is just a small Android Nagios client experiment.

The name is also a nod to Qnagstamon-style ideas, but this is a separate project.

## Disclaimer

This project is not affiliated with Nagios, Nagstamon or aNag

Use it at your own risk. If it wakes you up at 3 AM for the wrong reason, I am sorry. If it does not wake you up at 3 AM when it should, I am even more sorry.

## License

GPL-3.0-or-later


