# DroidDebugHunter — first run

**English** | [Русский](README-FIRST.ru.md)

Java is included. You do not need to install a system JDK/JRE.

- **Windows:** extract the entire ZIP and run `DroidDebugHunterByCompose.exe`, keeping `app` and `runtime` beside it. Or install the MSI.
- **macOS:** open the extracted `.app`, or copy it from DMG to Applications. Choose arm64 for Apple Silicon or x64 for Intel. Builds are unsigned and not notarized.
- **Linux x64:** run `./bin/DroidDebugHunterByCompose` from the extracted directory, or install the DEB. A graphical desktop is required.

HAR analysis works immediately. Try [the synthetic sample](https://github.com/MaiklGureev/DroidDebugHunterByCompose/blob/v1.1.2/examples/sample.har).

For a phone, open Settings → Tools (`Настройки → Инструменты`) and configure ADB, Python 3.14 and the tools directory. Preparation downloads optional components only when requested. Public archives do not include a separate Python interpreter, ADB, Frida servers or WireGuard APK.

Confirm USB debugging, CA installation/trust and VPN permission on Android. Open capture settings beside Start on Traffic (`Трафик`) and follow the offline Help. English and Russian manuals are included in `runtime/help` inside app resources and extracted into the user data directory. Switch English/Russian in Settings → Interface language (Настройки → Язык интерфейса). It applies immediately and is saved automatically; offline Help follows the selected language.

[Releases and SHA-256](https://github.com/MaiklGureev/DroidDebugHunterByCompose/releases).

Project code: MIT; see `LICENSE`. Third-party terms: `THIRD_PARTY_NOTICES.md` / `THIRD_PARTY_NOTICES.ru.md`.
