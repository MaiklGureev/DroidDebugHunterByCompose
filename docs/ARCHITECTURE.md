# Compose application architecture

**English** | [Русский](ARCHITECTURE.ru.md)

- `AppState`: screen state, SupervisorJob + Main, session ownership, event queues and batched UI updates every 100 ms.
- `Processes`: ProcessBuilder arguments without an intermediate shell, concurrent bounded stdout/stderr reads, timeouts, and owned process-tree termination/reaping on cancellation.
- `Adb`: fixed commands, remote-shell escaping, current Android profile/UID checks, app data/permissions and ELF/ABI validation.
- `CaptureSession`: one Python/Frida client, `ready` handshake, `DDH_HTTP_EVENT:` protocol, owned ADB forwards/reverses and a CA/VPN store lock.
- `ProxyLease`: snapshots all five system proxy settings and restores them only while they remain unchanged/owned by the session.
- `LogcatSession`: ADB stream, firmware/UID capability checks, target revalidation every five seconds and termination on profile changes.
- `Models`: immutable data, HAR import/export, exact body bytes, shell-specific cURL, log parsing/filtering/export.
- Compose UI: virtualized lists, separate result/detail windows, shared device/app selection, settings and offline help.

The UI performs no blocking I/O. Long commands run on IO; parsing/filtering runs on Default. Coroutine cancellation releases resources. NonCancellable is reserved for bounded termination/restoration.

HTTP uses a bounded Channel with backpressure. Logcat uses a bounded Channel with overload-drop accounting. Record-count and memory budgets protect long sessions. Immutable request updates are indexed by ID so detail windows receive subsequent responses.

`HostPlatform` detects OS/CPU, executable names and the native data directory. `ToolResolver` checks explicit paths, a local bundle, virtual environments, Android SDK and PATH. Tool paths are saved per OS/CPU, with Windows-specific legacy migration.

External tools remain separate from JVM code. Bundled scripts/help are extracted into the user data directory. Packaged apps use `compose.application.resources.dir`; source builds use `desktop-resources/<os>-<arch>/tools`. A shared Python script prepares tools on the target OS. jpackage includes a private Java runtime.

Gradle `run` copies the application JAR to a unique temporary directory before starting Java, then removes it after the process exits. Rebuilding `build/libs` cannot invalidate a running process's lazy coroutine classes. `--runtime-check` uses isolated data to exercise device selection/shutdown as well as report Java/platform metadata.

The original WPF project is not needed at runtime. Its Python/JS agents and Android DEX helper were ported with source. Capture runs in a separate Python process because Frida/mitmproxy use native bindings; control, events, cleanup and user workflows are implemented in Kotlin.
