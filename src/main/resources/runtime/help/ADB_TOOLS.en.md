# ADB operations

**English** | [Русский](ADB_TOOLS.md)

These operations require ADB, without Python or Frida. Select a device, refresh Apps (`Приложения`) and choose a package in the current Android user profile. Search includes label, package, UID and App ID. Use the package name when Android cannot expose labels.

| Section | Operations |
| --- | --- |
| Launch/process | Launch, force-stop, restart, launch/restart with Java/JDWP. |
| Data | Clear, clear-and-launch, uninstall for the selected profile with confirmation. |
| Permissions | Read declared dangerous runtime permissions, grant/revoke. |
| App diagnostics | Package/APK paths, processes, recent logs, Android app settings. |
| Network/device | Wi-Fi/mobile data, IP/routes/proxy, Wi-Fi settings, Android information and processes. |

Network/general actions are on ADB tools (`ADB-инструменты`) and do not require a package. Availability depends on Android, firmware and device policy. Ordinary commands do not request root automatically.

## Target and confirmation

Commands bind to serial, profile and package; refresh after switching profiles. Clearing removes app data/settings. Uninstallation targets the selected profile. Check device/package before confirming. Cancellation stops the owned command but does not undo completed Android changes.

Launching requires a launcher activity. JDWP requires a debuggable APK and IDE/debugger attachment; it is separate from Frida. Restart/force-stop can terminate an instrumented process/capture.

Grant/revoke applies to dangerous runtime permissions declared by the app. It cannot grant signature permissions or special access such as Accessibility. Android may reject a change because of SDK/policy; revocation can terminate the app.

## Results and files

Results open in separate windows. Copy all (`Копировать всё`) and Save TXT (`Сохранить TXT`) preserve full output; Esc closes the window. Compare multiple windows if needed. Recent logs is a finite snapshot of the selected app's processes, not an automatic fallback to all-system Logcat.

ADB tools provides file push/pull and APK installation. Use accessible Android paths; private data is subject to device permissions. Turning off Wi-Fi can disconnect wireless ADB; restore networking on the phone or use USB.

Remove app data/secrets before publishing output. See [connection setup](ANDROID_SETUP_GUIDE.en.md) and [streaming Logcat](LOGCAT.en.md).
