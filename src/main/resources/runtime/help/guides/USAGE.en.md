# DroidDebugHunter · Compose — user guide

**English** | [Русский](USAGE.md)

A Kotlin + Compose Desktop app for Windows, macOS and Linux. Native packages include Java; a system JDK/JRE is not required. Source development uses JDK 17 and `gradlew run` / IDEA's `desktop` configuration. See [platform setup](../setup/PLATFORMS.en.md).

## Interface language

Choose English or Русский in Settings → Interface language. The interface and Help update immediately; the choice is saved automatically. The initial language is Russian for ru system locales and English otherwise. Captured data, user text and existing operation output retain their original content.

## Get started

Analyze HAR immediately: Ctrl+O and choose a file, or try the repository's synthetic sample. No phone, ADB or Python is needed.

For Android, open **Connection** and select a capture method. **Requirements and next steps** shows tool status and the actions for that method. **Open tool settings** takes you to the tool versions, paths and download options. Use **Prepare missing tools** for the current method or **Download and prepare** for a single component. **Manual download** opens links and placement instructions. See [tool preparation](../setup/DOWNLOADS.en.md).

Open **Connection**, choose USB, Wi-Fi, or Emulator, then select the device. For USB, enable debugging and confirm the RSA key on the phone. Android 11+ also supports Wi-Fi pairing with a six-digit code followed by connection to its separate debugging port. See [Android setup](../setup/ANDROID_SETUP_GUIDE.en.md).

## Devices and apps

Devices (`Устройства`) provides the ADB device list and device diagnostics, including root checks. Apps (`Приложения`) lists packages in the current Android user profile; refresh and select a target. Search includes app name, package, UID and App ID (`UID % 100000`, not PID). Package names remain available if labels cannot be read.

Operations target the selected serial/profile. Refresh apps after changing Android profiles. Clearing data and uninstalling require confirmation. JDWP is Java debugging for a debuggable APK, separate from Frida. See [ADB operations](../android/ADB_TOOLS.en.md).

## Capture traffic

Choose a capture method and prepare it on **Connection**. For Frida or WireGuard, select the target app on **Traffic** before checking readiness. Start and stop capture on **Traffic**. Readiness checks do not replace manual CA installation, VPN consent or validation of actual captured data after startup.

| Mode | Requirements and limits |
| --- | --- |
| HTTP(S) proxy | Device-wide capture for apps that use Android's system proxy over ADB reverse. HTTPS requires CA trust. Package/UID are unknown. |
| Frida · OkHttp | OkHttp 3/4; a root server or debuggable + run-as/JDWP + Gadget. |
| Frida · Native TLS | Accessible native TLS functions in the selected process. HTTP/1 and HTTP/2; hidden/static functions and HTTP/3 may be unavailable. |
| VPN · WireGuard | WireGuard, UDP access to the computer, an allowlisted package profile and Android VPN consent. HTTPS requires CA trust. |

For one app, choose WireGuard or Frida. **Selected app only** filters the traffic list by the exact package and compares device and UID when available. Turn it off to review other saved requests. **Set up WireGuard for one app** on the proxy screen opens the VPN setup.

Frida client/server/Gadget must share the same version (target 17.26.2) and match the relevant ABI; other matching versions are allowed with warnings. VPN package attribution comes from the profile allowlist, not UID detection from packets. Avoid competing capture sessions for the same device. See [step-by-step setup](../setup/ANDROID_SETUP_GUIDE.en.md).

## Inspect requests

Filters and sorting apply to accumulated traffic and HAR. Double-click, Enter or Details (`Детали`) opens a separate request window; compare multiple windows if needed. Inspect headers, request, response, cURL and diagnostics supplied by the engine. The window receives later updates for its request.

Body views: Auto, Text, JSON, HTML text, Image, HEX, Base64. Saving the body preserves captured bytes; saving the view exports displayed text. Partial captures are explicitly marked. See [body viewing](BODY_VIEWER.en.md).

Choose PowerShell or POSIX shell on the cURL tab. Commands can contain cookies, tokens and request bodies; review before sharing. HAR export saves visible HTTP requests and partial-capture metadata. Diagnostic streams are not converted into invented HTTP requests.

## Application log

**Settings → Application log** shows the location of `logs/application.log` in the application data directory. It records startup, operations, connection and tool diagnostics, and errors. The active log rotates at 5 MiB and keeps one previous file. **Export log** saves the retained diagnostic history as a UTF-8 file. **Clear log** clears both files and the on-screen journal after confirmation. HTTP captures and Android Logcat rows use their own exports.

## Logcat

Logcat uses ADB without Python/Frida and can run alongside traffic capture. Record the system or selected UID, choose buffers/history, and filter text/tag/level/PID/TID/UID. Ctrl/Shift selects multiple rows; Ctrl+C copies them. Export TXT, CSV or JSONL. See [Logcat](../android/LOGCAT.en.md).

## Storage and shutdown

Screen navigation preserves streams; changing/losing a device stops them. Normal shutdown cancels operations, reaps owned processes and restores owned proxy/ADB settings. It does not stop the shared ADB server. After forced termination, verify Android proxy/VPN manually.

Data lives in `%LOCALAPPDATA%\DroidDebugHunterCompose` (Windows), `~/Library/Application Support/DroidDebugHunterCompose` (macOS), or `$XDG_DATA_HOME/DroidDebugHunterCompose` / `~/.local/share/DroidDebugHunterCompose` (Linux). CA/keys persist. Reset CA/VPN (`Сбросить CA и VPN…`) makes a backup. Remove installed Android certificates/profiles manually when no longer needed.

Record count and memory are bounded. Older records are evicted and Logcat overload drops counted separately. Export cannot recover evicted/dropped data.

Ctrl+O: HAR; Ctrl+K: commands; F1: Help; Enter/double-click: details; Esc: close window. macOS also supports ⌘ shortcuts.

Project code is under MIT; see [LICENSE](../legal/LICENSE). [Third-party notices](../legal/THIRD_PARTY_NOTICES.en.md).
