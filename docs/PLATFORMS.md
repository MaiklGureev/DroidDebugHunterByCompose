# Windows, macOS and Linux

**English** | [Русский](PLATFORMS.ru.md)

The app detects OS/CPU automatically. **Connection** shows the current platform. Choose a tools directory, ADB and Python (target 3.14.10; alternatives allowed); a virtual-environment interpreter is supported. Enter one executable path or PATH command name, without arguments or extra quotes.

## Java for users and developers

Native distributions include a **private Java runtime**. Users do not need a system JDK/JRE. Keep the whole Windows app directory (`runtime/`, `app/` and `.exe`); the executable alone is incomplete. macOS `.app` and Linux app directories also contain Java.

JDK 17 is needed only for development, Gradle, tests and packaging. `gradlew run` is a source-development launcher rather than a native distribution. Use the IDEA `desktop` configuration; Gradle's temporary JAR copy remains stable during subsequent builds.

## Directories and settings

| OS | Data directory | Build-time tools directory |
| --- | --- | --- |
| Windows x64 | `%LOCALAPPDATA%\DroidDebugHunterCompose` | `desktop-resources/windows-x64/tools/` |
| macOS Apple Silicon | `~/Library/Application Support/DroidDebugHunterCompose` | `desktop-resources/macos-arm64/tools/` |
| macOS Intel | Same macOS data directory | `desktop-resources/macos-x64/tools/` |
| Linux x64 | `$XDG_DATA_HOME/DroidDebugHunterCompose` or `~/.local/share/DroidDebugHunterCompose` | `desktop-resources/linux-x64/tools/` |

ARM64 hosts use `windows-arm64` / `linux-arm64` directories, but compatible tools/wheels need separate verification; these are not currently published release targets. If official Linux ADB does not match your CPU, install native ADB from the distribution and configure its path.

Packaged bundles are read from the app's `tools/` resources. You can select any accessible directory when no bundle exists. Tool paths are saved separately by OS/CPU; old Windows settings migrate only on Windows. UI settings, including interface language, are shared between platform profiles. Settings → Interface language switches English/Russian immediately and saves the choice for subsequent launches. Without a saved choice, ru system locales use Russian and other locales use English.

ADB discovery: bundle → `ANDROID_SDK_ROOT` / `ANDROID_HOME` and standard Android SDK locations → PATH. Python discovery: local `python/` or `.venv/` → PATH; macOS also checks standard Homebrew locations. Leave executable fields empty for discovery. Explicit paths have priority.

## Prepare tools

Prepare on the **target OS and CPU**. Windows executables/wheels cannot be reused as a macOS/Linux bundle. Android server/Gadget files are selected separately by the phone/app ABI.

In the app, apply paths, select a component, then Check bundle (`Проверить комплект`) or Download/prepare (`Скачать / подготовить`). Choose a writable directory; the installation directory may be read-only.

macOS/Linux with Python (target 3.14.10; alternatives allowed):

```sh
python3 prepare-tools.py --action init
python3 prepare-tools.py --action download --component all
python3 prepare-tools.py --action check
```

Windows:

```powershell
python prepare-tools.py --action download --component all
# Or reuse an existing Windows bundle:
.\prepare-tools.ps1 -Source 'D:\FridaDebugTool' -Action check
```

Use `--root <directory>` for another location. `--offline` disables network downloads. Preparation uses `pip --target` in the selected bundle. The frozen requirements target Python 3.14.10; other Python/package versions use dependency metadata, and native wheel availability depends on OS/CPU. The full preparation output includes pip errors. Platform markers omit Windows-only packages on Unix.

Retain Platform Tools libraries, Google notices and Unix executable permissions. Linux USB access depends on device permissions/udev rules in your distribution; the app does not change them automatically.

## Build and verify

Build on the **target OS**; Windows cannot produce macOS `.app` files. CI uses native Windows, Ubuntu and macOS runners. Unix portable archives use `.tar.gz` to preserve executable permissions. Extract the entire archive.

```sh
./gradlew test createDistributable
# macOS:
./gradlew packageDmg
# Linux:
./gradlew packageDeb
```

Windows:

```powershell
.\run.ps1 -Task test
.\run.ps1 -Task createDistributable
.\run.ps1 -Task packageMsi
```

Output: `build/compose/binaries-1.1.3/main/app/`. Only host OS/CPU tools are bundled. Without tools, the app still provides HAR analysis and external-tool configuration.

Check Windows with system Java paths disabled:

```powershell
.\scripts\smoke_windows.ps1 -ApplicationRoot .\build\compose\binaries-1.1.3\main\app\DroidDebugHunterByCompose
```

On macOS/Linux: `python3 scripts/package_release.py --runtime-check`. These checks verify private Java, device selection and shutdown using isolated data. They do not require uninstalling the developer JDK or connecting Android.

## Export and shortcuts

cURL defaults to PowerShell on Windows and POSIX shell on macOS/Linux. Switch the format in request details. Binary bodies use a temporary file to preserve captured bytes.

macOS supports ⌘ alongside Ctrl: ⌘O for HAR, ⌘K for the command palette, ⌘C for selected log rows. Current builds are unsigned; Apple notarization is not configured.

User-local installation, offline cache and target versions: [tool setup](../src/main/resources/runtime/help/DOWNLOADS.en.md).
