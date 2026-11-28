# Compose application architecture

**English** | [Русский](ARCHITECTURE.ru.md)

## Packages and ownership

The desktop entry point is [Main.kt](../src/main/kotlin/Main.kt). Sources under `hunter` are grouped by layer and feature.

| Package | Responsibility |
| --- | --- |
| `app` | `AppState` constructs and connects the ViewModels, owns the application scope, and coordinates navigation, the command palette, the journal, confirmations, and finite operations with shared busy, error, and cancel controls. |
| `core` | Platform detection and paths, runtime resources, localization, JSON, subprocess management, and the shared `StreamSession` contract. |
| `ui` | Shared Compose components, theme, desktop shortcuts, and output windows. Feature-specific screens and components live in `features.<feature>.ui`. |
| `features.android` and `features.connection` | `Adb` handles commands, shell escaping, and target validation. `ConnectionViewModel` owns device and app selection, permissions, the capture mode, and diagnostics. `CaptureReadiness`, `ConnectionAssets`, and `ManagedFridaServer` handle readiness checks, CA/VPN files, and the server started by this application. |
| `features.traffic` | `TrafficViewModel` owns HTTP capture, accumulated exchanges, and the IDs of open detail windows. It handles HAR import/export and body saving. `CaptureSession` manages the capture process and its resources. |
| `features.logcat` | `LogcatViewModel` owns recording options, rows, counters, and export. `LogcatSession` handles the ADB stream and Android target checks. |
| `features.tools` | `ToolSetupViewModel` owns detected versions, per-component results, download progress, and the setup log. Provisioning, inspection, downloads, and file layout live in the service code in the same package. |
| `features.settings` | `SettingsViewModel` publishes the current settings, applies language and theme changes, and serializes writes. `Settings` defines the settings and their on-disk format. |
| `features.help` | `HelpViewModel` owns topics, history, language, and document loading. The Markdown parser and resolver handle document content and links. |

## State and operations

Screens collect the ViewModel's read-only `StateFlow` and call its methods for user actions. The ViewModel updates its private `MutableStateFlow` and publishes a new snapshot. Where a consumer only needs one value, it can subscribe to a narrower flow, such as the recording flag or one open HTTP exchange. Temporary interaction state such as filter text, an open dialog, or an unapplied form draft stays in Compose.

`AppState` wires cross-feature operations through constructor callbacks. For example, a device transport change stops capture and Logcat, clears the app and permission selection, and invalidates diagnostics. A readiness check publishes results only if the settings, device, app, and mode still match the snapshot taken at its start. The capture command validates the selected target before handing off to `TrafficViewModel`.

Feature code sends finite user operations through `AppState.task` when they use the shared busy, error, and cancel controls. Long-lived session jobs run in the application scope, built on `SupervisorJob` and `Dispatchers.Main.immediate`. Work that blocks on files, network, or subprocesses uses `Dispatchers.IO`. Expensive parsing and filtering use `Dispatchers.Default`.

## Sessions, startup, and shutdown

`Main.kt` loads settings and creates one `AppState` for the Compose application. Startup extracts the bundled resources, notifies Help, initializes tool folders, and refreshes Android devices. ViewModels stay active across tab switches. Changing or losing the selected Android transport stops both streams. A process-exit callback checks that the exiting session is still the owned session before updating state.

Traffic and Logcat drain their event queues every 100 ms and publish snapshots in batches. The bounded HTTP channel provides backpressure. The bounded Logcat channel counts rows dropped when its buffer is full. Both also enforce retention limits by record count and estimated memory. Traffic merges capture events by request ID so an open detail window receives later response data.

`CaptureSession` handles the capture engine's `ready` handshake and `DDH_HTTP_EVENT:` protocol. It owns its ADB forwards and reverses and holds a lock on the CA/VPN store. `ProxyLease` snapshots the five Android proxy settings and restores only values still owned by the session. `LogcatSession` checks firmware and UID support and revalidates its target every five seconds when recording a selected app. Both use `StreamSession` for start and stop. `Processes` uses argument lists with `ProcessBuilder`, bounds output reads, applies timeouts, and terminates its own process trees on cancellation.

On close, `AppState.shutdown()` cancels and joins the active finite operation, stops both streams, then stops the owned Frida server and flushes settings before cancelling the application scope. Required cleanup and the final settings flush use `NonCancellable`.

## Settings, tools, and offline help

Settings changes publish immediately. A conflated save signal and a mutex coordinate disk writes so the final flush saves the latest snapshot. Tool setup applies only the tool fields of its draft. It reports partial results by component, preserves successful steps on retry, and discards inspection results for a tool configuration that has since changed.

Bundled Help follows the interface language, including its navigation history. The ViewModel reads files on IO and parses Markdown on Default. Local links resolve within the current document root, which is the bundled help directory or the directory of a file opened by the user.

`HostPlatform` detects OS/CPU, executable names and the native data directory. `ToolResolver` checks explicit paths, a local bundle, virtual environments, Android SDK and PATH. Tool paths are saved per OS/CPU, with Windows-specific legacy migration.

External tools remain separate from JVM code. Bundled scripts/help are extracted into the user data directory. Packaged apps use `compose.application.resources.dir`; source builds use `desktop-resources/<os>-<arch>/tools`. A shared Python script prepares tools on the target OS. jpackage includes a private Java runtime.

## Verification

Run the Gradle `test` task for the Kotlin tests. The ViewModel tests cover device selection and stale diagnostics, session lifetime between subscribers, tool retries, settings persistence, and Help navigation. They use injected operations or session factories where needed. `--runtime-check <report.json>` exercises application initialization, language and theme persistence, bundled Help, and shutdown with isolated data.

Gradle `run` launches a temporary copy of the application JAR and removes it after exit. That keeps the running process's classes available during a rebuild. Capture runs in a separate Python process for the Frida and mitmproxy native bindings. Kotlin coordinates the process, events, cleanup, and user workflows.

See the [Kotlin contributor instructions](../src/main/kotlin/AGENTS.md) for rules on extending the code.
