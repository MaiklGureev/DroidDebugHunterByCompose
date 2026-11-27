# Android setup for the Compose application

**English** | [Русский](ANDROID_SETUP_GUIDE.md)

Use devices/apps you are authorized to inspect. Commands target the selected serial and Android user profile. Confirm Android actions on the phone. Local readiness does not prove HTTPS trust or successful instrumentation inside a process.

## 1. Prepare the computer

Native Compose packages include Java. Devices need ADB; live capture targets Python 3.14.10 (alternatives are allowed with compatibility warnings) and the selected mode's components. In Settings → Tools (`Настройки → Инструменты`), configure paths, apply, prepare components and check the bundle. See [tools](DOWNLOADS.en.md) and [platforms](PLATFORMS.en.md).

Windows, macOS and Linux need their own ADB/Python/wheels. Android server/Gadget binaries depend on device or app ABI. Frida client/server/Gadget must match each other; the target is 17.26.2, with other matching versions allowed. Normal startup downloads nothing.

## 2. Connect Android

Open **Devices → Connect Android** and choose **USB**, **Wi-Fi**, or **Emulator**.

Enable Developer options and USB debugging. Connect a data-capable cable, unlock the phone and confirm the computer's RSA key. Refresh devices and select a row in `device` state. For `unauthorized`, confirm the phone prompt; for `offline`, check cable/debugging and reconnect. Linux may need distribution udev configuration; Windows may need the manufacturer's USB driver.

Android 11+: enable wireless debugging. On the **Wi-Fi** card, enter the pairing address and six-digit code in step 1. In step 2, use the connection address from the main Wireless debugging screen or choose an address found by ADB discovery. If the computer is already paired, start at step 2.

For an Android Studio emulator, start the virtual device in Device Manager, wait for Android to boot, and refresh from the **Emulator** card. Use **Wi-Fi** for emulators that provide an ADB network address.

Refresh Apps (`Приложения`) and select the current Android profile's package. Refresh again after switching profiles. UID is not PID; App ID = UID % 100000. See [ADB operations](ADB_TOOLS.en.md).

## 3. Choose a mode

Open capture settings beside Start on Traffic (`Трафик`). Check readiness (`Проверить готовность`) validates tools and target. CA installation/trust, profile import and VPN consent are manual Android steps. Trigger a fresh network action in the target app to validate actual capture.

### HTTP(S) proxy

Select the device and HTTP(S) proxy. For HTTPS, Prepare CA (`Подготовить CA`), then Send CA (`Передать CA`). On Android, install `Download/DroidDebugHunter-CA.cer` through **CA certificate / Сертификат CA** in the user trusted-CA store. Do not choose the VPN/app client-certificate or Wi-Fi certificate category.

The target app must use Android's proxy and trust the CA. Start capture and repeat the action. The engine listens on computer loopback; Android reaches it over an owned ADB reverse. Existing proxy/PAC settings are saved and restored on normal stop if another owner has not changed them. A regular HTTP proxy cannot identify package/UID.

If HTTP works but HTTPS fails, check CA trust and your debug APK's pinning. If no requests appear, check proxy settings, ADB reverse and whether the app uses that transport. A custom network stack may ignore the system proxy.

### Frida · OkHttp with root

Open **Check Frida connection** in capture settings or Devices. The checklist shows the active Python Frida version, device ABI, installed server version when available, and the result of a real Frida request through a temporary ADB forward. An independently started server can respond without exposing its exact version. Follow the proposed action to prepare tools, install or start the matching server, or choose an app. Confirm root access on Android when starting the server. `adb root` works on supported emulators/debug firmware. The server listens on Android `127.0.0.1:27042`.

Select a package, OkHttp mode and optionally Launch app at startup (`Запустить приложение при старте`). Disable the Gadget option when using the root-server scenario. Start capture and repeat the network action from process startup. Disable launch-at-start to attach to a running app.

The agent targets compatible OkHttp 3/4 APIs. If they are absent, check the process, cache and network stack, then try Native TLS or proxy/VPN. These agents do not disable certificate verification. `SSLHandshakeException` alone does not prove pinning.

### Frida · Native TLS

Use the same root server or debuggable/Gadget setup. Select Native TLS and the package. The agent observes accessible TLS functions without modifying buffers or certificate-verification results, decoding supported HTTP/1 and HTTP/2/HPACK.

Hidden/static TLS functions, other processes and HTTP/3 may be unavailable. Unrecognized bytes remain diagnostic fragments rather than invented HTTP requests. If VPN shows only images, investigate CA trust/transport: disable its tunnel before comparing with Frida, repeat the action and inspect Logcat.

### Rootless Frida: debuggable + Gadget

Use a **debuggable APK** with working `run-as` and JDWP. Prepare Gadget matching the active Frida client (target 17.26.2) for the app ABI; a 32-bit app on a 64-bit device may need a different ABI.

Enable Rootless: debuggable + run-as + Gadget (`Без root: debuggable + run-as + Gadget`) and app launch at startup. Frida uses ADB/JDWP and the local Gadget. Arbitrary inaccessible running processes cannot be attached this way without root. Check your APK's debuggable flag and `run-as` if startup is rejected.

The checklist verifies `run-as` and the local Gadget ELF for the selected app ABI and client version. Gadget connects when capture launches the app.

### VPN · WireGuard

1. Select the device, package and VPN mode. Set a computer IP/DNS name reachable from Android and a UDP port. Genymotion VirtualBox commonly uses `10.0.3.2`, Android Emulator `10.0.2.2`; a physical phone uses the computer's LAN address.
2. Prepare profile (`Подготовить профиль`), then Send to WireGuard (`Передать в WireGuard`). If the client is absent, a prepared local APK is used. On Android, import `Download/ddh.conf` using **+ → Import from file**.
3. For HTTPS, prepare/send/install the CA through **CA certificate**. The target app must trust it; VPN does not disable pinning.
4. Allow inbound UDP to the chosen port for the actual Python executable in the firewall. Start capture on the computer, enable the Android tunnel and confirm VPN consent.
5. Enable WireGuard **Remote control** for automatic shutdown of the owned tunnel. Otherwise verify tunnel shutdown manually when stopping capture.

VPN package metadata comes from its allowlist; manually changing that list invalidates the attribution. Regenerate/reimport after changing endpoint, port or package. Another VPN, blocked UDP, an incorrect address or emulator networking may prevent connectivity. Keys/profiles persist after normal stop.

## HTTPS in your debug APK

Installing a user CA does not make every app trust it. In your own Android project, use a debug-only Network Security Configuration while preserving any existing configuration. For example, add to the debug configuration:

```xml
<network-security-config>
    <debug-overrides>
        <trust-anchors>
            <certificates src="user" />
        </trust-anchors>
    </debug-overrides>
</network-security-config>
```

Reference the resource with `android:networkSecurityConfig` in the debug manifest. Do not ship test-CA trust in production. Library-specific pinning may need separate debug configuration. See [Android Network Security Configuration](https://developer.android.com/privacy-and-security/security-config).

## HAR, Logcat and troubleshooting

Export HAR from a browser's DevTools Network panel and open it with Ctrl+O. If capture only produces diagnostic events, inspect messages/fragments in details and the journal rather than expecting HTTP without decoding. See [usage](USAGE.en.md), [bodies](BODY_VIEWER.en.md) and [Logcat](LOGCAT.en.md).

No device: check ADB/serial/RSA. No Frida: versions, ABI, root/Gadget and engine output. No HTTPS: CA, debug APK trust, pinning and network stack. No VPN: address, UDP, profile and consent. No new traffic: trigger an uncached action in the correct process. Save diagnostic output as TXT and remove secrets before sharing.

## After debugging

Stop capture/Logcat normally. Owned proxy/forward/reverse settings are restored and owned Frida processes terminated; the shared ADB server stays running. After forced termination, check Android proxy/tunnel/processes manually. Remove unused Android CA certificates, imported VPN profiles and Download copies yourself.

CA/keys and settings persist in the Compose user data directory listed in [platforms](PLATFORMS.en.md). Reset CA/VPN creates a backup; install the new CA/profile again afterwards.
