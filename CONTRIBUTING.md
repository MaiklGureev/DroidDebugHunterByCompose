# Contributing

**English** | [Русский](CONTRIBUTING.ru.md)

Use JDK 17 and the Gradle wrapper. Before a pull request, run `./gradlew test` (Windows: `.\run.ps1 -Task test`). Unit tests do not require a phone or Python; the optional proxy integration test requires local tools. Report Android checks separately: device/API/ABI, capture mode, debuggable/root status and observed results.

Run blocking I/O on `Dispatchers.IO`, computation on `Dispatchers.Default`, and Compose state updates on `Dispatchers.Main`. Use `Processes` / `StreamingProcess` for subprocesses. Cancellation must reap only owned processes. Do not concatenate user input into shell commands or stop the shared ADB server during automatic cleanup.

Bind Android operations to the selected serial, user profile and UID. Do not present an unavailable body as a complete empty body. Data clearing and uninstallation require explicit confirmation identifying the target.

Add behavior tests for parsing, exports, resource ownership and cancellation changes. Check UI changes in both themes and at 1100×720. Prefer the shared `desktop` configuration for development: its temporary JAR remains stable while another build runs.

Do not commit external tools, APKs, Python distributions, build output, real HAR/Logcat, certificates, VPN profiles, private keys, personal paths or local IDE settings. Put synthetic examples in `examples/`.

After staging, run `python scripts/check_repository.py`. It checks prohibited paths, large files and common private-key formats, reporting filenames only. Also review the diff manually.

When adding agents/help files, run `python scripts/update_runtime_manifest.py`. Interpreter caches (`__pycache__`, `.pyc`) must not enter the manifest or JVM resources. Test platform changes on Windows/macOS/Linux; tools and native Python wheels must match OS/CPU.

English is the default repository language. Keep each English document and its Russian counterpart aligned and link them near the top. Embedded help retains Russian filenames used by the UI and provides `.en.md` counterparts. Update the documentation index and runtime manifest when adding help.

In a PR, describe the problem, resulting behavior, validation and limitations. Contributions to the project's own code are under the [MIT License](LICENSE). Preserve third-party licenses and notices.

Interface text uses English message keys in `tr(...)` and matching `src/main/resources/i18n/en.json` / `ru.json` catalogs. Keep numeric argument placeholders identical in both languages. Use stable IDs/enums for selections, not translated labels. Never translate captured payloads, raw logs or user-entered text.
