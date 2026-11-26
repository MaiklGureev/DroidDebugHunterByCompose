# Logcat

**English** | [Русский](LOGCAT.md)

Select a device and open Logcat. Only ADB is required. Start recording (`Начать запись`) starts the stream; Stop (`Остановить`) terminates the owned client. Navigation preserves recording; changing the device or closing the app stops it.

## System or UID

With App (UID) (`Приложение (UID)`) disabled, capture available system messages. For an app, enable it, refresh apps and select a package. Capture uses the current profile's full UID, including new PIDs after a restart; the app itself is not launched automatically.

Profile/UID are checked before startup and every five seconds. Changes stop the target stream. When firmware lacks `--uid`, exact local UID filtering may be used if lines contain UIDs. If exact filtering is impossible, the app reports it instead of silently switching to all-system logs.

A shared UID can cover multiple packages. Isolated processes with other UIDs are excluded. ActivityManager messages about an app may come from a system UID and be absent in app-only capture.

## Buffers, filters and rows

Choose buffers (`main + system + crash`, `all`, individual buffers) and history size before starting. Text/Regex, minimum level, tag, PID, TID, UID and device filters apply to accumulated rows. RE2/J does not support lookbehind/backreferences; invalid expressions show an error.

Disable auto-scroll to inspect history. Ctrl/Shift + click selects multiple rows; Ctrl+C copies selected visible rows. Enter/double-click opens details.

## Export and memory

Export snapshots selected, filtered or all accumulated rows while recording may continue. TXT retains original text; JSONL includes structured fields/raw; CSV separates columns and protects formula-like values. TXT/JSONL preserve exact original lines.

Clearing the window does not clear Android buffers. Older rows are evicted by the count limit or approximate 64 MiB memory budget. The bounded queue counts overload drops. Received/evicted/dropped counters expose data loss; exports cannot recover lost rows.

[ADB operations](ADB_TOOLS.en.md), [user guide](USAGE.en.md).
