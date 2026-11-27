# DroidDebugHunter — first run

**English** | [Русский](README-FIRST.ru.md)

Java is included. You do not need to install a system JDK/JRE.

- **Windows:** extract the entire ZIP and run `DroidDebugHunterByCompose.exe`, keeping `app` and `runtime` beside it. Or install the MSI.
- **macOS:** open the extracted `.app`, or copy it from DMG to Applications. Choose arm64 for Apple Silicon or x64 for Intel. Builds are unsigned and not notarized.
- **Linux x64:** run `./bin/DroidDebugHunterByCompose` from the extracted directory, or install the DEB. A graphical desktop is required.

HAR analysis works immediately. Try [the synthetic sample](https://github.com/MaiklGureev/DroidDebugHunterByCompose/blob/v1.1.3/examples/sample.har).

For a phone, open **Connection** and choose a writable tools directory. Each tool shows its status, detected version, download version, folder and options. Use **Download and prepare** for one tool or **Prepare capture tools** for the tools required by the current capture method. Manual download links are also available. Portable uv can prepare Python without a system interpreter; existing Python/venv and offline files are also supported. Downloads start only after an explicit button press and show progress, a live log and cancellation. The wizard does not request elevation or change the system PATH.

Check detected versions against the targets; other versions are allowed with compatibility warnings. **Download links / folders** provides individual archive downloads, link copying and placement instructions. Missing directories are created on startup where permitted; **Folder structure** shows the manual layout. The full [tool setup guide](https://github.com/MaiklGureev/DroidDebugHunterByCompose/blob/v1.1.3/src/main/resources/runtime/help/DOWNLOADS.en.md) is also included in offline Help. Public archives do not bundle a separate Python, ADB, Frida servers or WireGuard APK.

Confirm USB debugging, CA installation/trust and VPN permission on Android. Choose the device and capture method on **Connection**, then start capture on **Traffic**. See the offline Help for setup steps. English and Russian manuals are included in `runtime/help` inside app resources and extracted into the user data directory. Switch English/Russian in Settings → Interface language (Настройки → Язык интерфейса). It applies immediately and is saved automatically; offline Help follows the selected language.

[Releases and SHA-256](https://github.com/MaiklGureev/DroidDebugHunterByCompose/releases).

Project code: MIT; see `LICENSE`. Third-party terms: `THIRD_PARTY_NOTICES.md` / `THIRD_PARTY_NOTICES.ru.md`.
