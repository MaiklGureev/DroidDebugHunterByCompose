# Tools, versions and setup

**English** | [Русский](DOWNLOADS.md)

HAR analysis needs no external tools. Java is included in native packages. ADB is needed for devices; Python and mode-specific packages are needed for capture. Check the tools you want and click **Prepare selected**. Normal startup downloads nothing.

## Target versions and alternatives

The reference configuration is recorded in `tool-setup/tool-versions.json` (reviewed 2026-11-27). These are targets, not a guarantee for every Android firmware or app.

| Component | Target | Compatibility / use |
| --- | --- | --- |
| Python | 3.14.10, 64-bit | The target proxy requires Python 3.12+. Other Python versions are allowed; package metadata, APIs and native wheel availability determine whether they work. |
| ADB / Platform Tools | 37.0.3 | Other SDK/PATH/portable versions are allowed. Older ADB may lack wireless pairing or other commands. Keep libraries, `NOTICE.txt` and `source.properties` together. |
| Frida client / server / Gadget | 17.26.2 | Alternative versions are allowed, but client/server/Gadget must match each other and Android ABI. Root is required for the ordinary server scenario; Gadget needs a compatible debuggable app. |
| frida-tools | 14.13.1 | This version requires Frida >=17.26.0,<18.0.0. For older/newer Frida choose compatible frida-tools too; the package resolver checks dependencies. |
| mitmproxy | 12.3.1 | Requires Python >=3.12. Alternative releases may have different Python/API requirements. |
| mitmproxy_rs | 0.12.15 | The target mitmproxy requires >=0.12.15,<0.13. Native wheels must match OS, CPU and Python. |
| WireGuard Android | 1.0.20260315 | Optional: VPN only. An already installed compatible Android app may be used. The local APK filename is reported separately from Android installation. |
| uv (setup helper) | 0.13.10 | Optional portable Python bootstrap. A user-selected uv executable is also supported. uv uses Astral python-build-standalone distributions. |

**Check** reports detected versions, executable/module paths, missing components and differences from targets. Other versions are not blocked solely for being different. Before capture, the relevant runtime versions are checked again and warnings are written to the journal. Frida server/Gadget selection follows the actually imported client version. Protocol mismatch, missing imports, unsupported APIs or incompatible binaries can still prevent an operation.

## Configure and download tools

On **Connection**, each tool shows its status, versions, bundle folder and local options. ADB is shared across USB, Wi-Fi and Emulator. Choose the Python executable and method in the Python section. The shared tool directory and download cache are always visible.

- **Manual download:** open the component's sources, copy links and follow the placement instructions. Use **Check tools** after placing files or saving an executable path.
- **Download and prepare:** let the app download and prepare this component in the selected folder. To prepare a batch, check the desired tools and click **Prepare selected**. Selecting a component or opening its links does not start a download.

If a component fails, independent steps continue. For example, WireGuard can be prepared even when Python is unavailable. Steps with missing prerequisites are marked **Skipped**. To retry, use **Select unfinished**, then **Prepare selected**.

Installation methods, versions, offline/cache settings and executable paths are saved per OS/CPU profile.

## Installation methods and restricted computers

1. **Portable Python via uv:** downloads a pinned, SHA-256-checked uv archive and creates a private Python/venv under the tools folder. Does not need a preinstalled Python, system JDK/JRE, PATH changes or administrator elevation. Python/seed-package installation reports its stages in the live log.
2. **Existing Python + local venv:** creates a venv using your selected interpreter. The interpreter needs `venv` and `pip`/`ensurepip`. On some Linux distributions these are separate OS packages.
3. **Existing Python + local packages:** uses the interpreter's pip with `--target` into component folders, without changing its global site-packages. Useful if venv creation is unavailable. Preparation helpers need Python 3.11+; the target proxy needs 3.12+.

All methods install packages into `frida-python/` and `proxy-python/`; the application adds those folders to `PYTHONPATH`. On a non-target interpreter or custom package versions, dependencies are resolved from package metadata rather than forcing the Python 3.14 snapshot. Import validation runs before replacing an existing component; a failed install preserves it.

Choose a writable user folder or click **Use app data folder**. If execution/downloads are restricted, try an approved existing interpreter/ADB, a local venv, another writable folder or approved offline files. The wizard does not elevate, run `sudo`, install drivers or change system policies. USB drivers/udev rules may require administrator assistance; Wi-Fi ADB is an alternative. Python package-manager examples are shown for manual use, including WinGet `--scope user`, Scoop and Homebrew; they may be unavailable or restricted.

Frida includes the h11/hpack dependencies for Native TLS decoding without the proxy package. Capture and proxy engines use separate Python package directories to avoid mixing dependency versions.

## Progress, cancellation and retries

The setup area shows the current stage and a live log. Direct file downloads show bytes and percentages when the server provides a size; otherwise the indicator is indeterminate. pip/uv installation shows stages and output rather than a fabricated overall percentage. **Cancel** stops owned processes and removes incomplete direct-download files. Completed cached downloads can be reused on retry. Do not move a managed Python installation after creation: venv paths can depend on its location.

## Offline preparation

Enable **Offline** and choose a cache folder. Optional **Local Platform Tools ZIP** imports an archive directly. Set the archive's revision in the ADB software block. A local uv executable can also be selected.

Cache layout:

```text
adb/platform-tools_r37.0.3-win.zip          # darwin/linux on those hosts
frida/frida-server-17.26.2-android-arm64.xz  # also arm, x86_64, x86
frida/frida-gadget-17.26.2-android-arm64.so.xz
wireguard/com.wireguard.android-1.0.20260315.apk
uv/uv-x86_64-pc-windows-msvc.zip            # matching host archive
wheelhouse/capture/                        # wheels for Frida + dependencies
wheelhouse/proxy/                          # wheels for proxy + dependencies
uv/                                       # uv's managed Python/package cache
```

Use the chosen versions in filenames. Offline mode makes no download attempts. Portable bootstrap also needs uv's previously populated Python and seed-package cache; copying only uv is insufficient. Alternatively use an existing Python with pip. Prepare complete wheelhouses for the same OS/CPU/Python. Manually supplied Frida archives receive ELF/ABI validation; their publisher checksums are not verified automatically. Automatic Frida downloads require GitHub's SHA-256 metadata. Custom ADB releases use HTTPS and local fingerprints; the target ADB archive also has a pinned SHA-256.

## Separate downloads and manual folder layout

Check the Frida client, server and Gadget together for the full bundle, or choose individual components. **Manual download** opens versioned URLs with **Download file**, **Copy link**, **Open link** and **Open destination folder** actions. **Download file** saves the original archive or APK in the cache. Python package links open the files page for the selected version so you can choose compatible wheels and dependencies. **Copy all links** includes placement instructions.

At startup the app tries to create missing component/cache directories. A read-only folder produces warnings without preventing startup or requesting elevation. **Check / create folders** retries after you choose another location. **Folder structure** shows your actual selected root and versions; you may create the same layout manually:

```text
tools/
├── platform-tools/      adb(.exe), libraries, NOTICE.txt, source.properties
├── frida-server/        frida-server-<version>-android-<ABI> (ELF)
├── frida-gadget/        frida-gadget-<version>-android-<ABI>.so
├── frida-python/        frida/, frida_tools/, dependencies, *.dist-info/
├── proxy-python/        mitmproxy/, mitmproxy_rs/, dependencies, *.dist-info/
├── android-tools/       com.wireguard.android-<version>.apk
├── .venvs/              Python environments
├── python-managed/      uv-managed Python
└── downloads/           original archives and offline wheels
    ├── adb/             Platform Tools ZIP
    ├── frida/           original .xz files
    ├── wireguard/       original APK
    ├── uv/              original uv archive and uv cache
    └── wheelhouse/
        ├── capture/     Frida wheels + dependencies
        └── proxy/       proxy wheels + dependencies

```

Keep ZIP/.xz archives in `downloads/`; use extracted ELF/.so in the working Frida directories. Keep APK intact. Do not copy `.whl` files into `frida-python/` or `proxy-python/`: install them with `pip --target <folder>`. Use `python -m pip download --only-binary=:all: --dest <cache>/wheelhouse/capture -r <runtime>/tool-setup/capture-requirements.txt` (and the proxy requirements for proxy) to build a complete target wheelhouse. For custom versions use explicit package version constraints instead. Prepare wheelhouses using the same OS/CPU/Python as the destination.

## Command-line preparation

From the repository root, using the selected Python 3.11+:

```sh
python3 prepare-tools.py --action init
python3 prepare-tools.py --action download --component adb
python3 prepare-tools.py --action download --component capture --version-frida 17.26.2 --version-fridaTools 14.13.1
python3 prepare-tools.py --action download --component proxy --offline --cache /path/to/cache
python3 prepare-tools.py --action check
```

Other options: `--root`, `--component all|frida|adb|server|gadget|capture|proxy|wireguard`, `--version-python`, `--version-adb`, `--version-mitmproxy`, `--version-mitmproxyRs`, `--version-wireguard`, `--adb-archive`. The CLI prepares components with an existing interpreter; use the desktop wizard to bootstrap Python itself. On Windows, use `python` or `prepare-tools.ps1` with `-Offline`, `-Cache` and component version parameters.

Android APK installation, CA trust, root access and VPN consent are separate explicit steps. Windows executables/wheels cannot be used as a macOS/Linux bundle. Keep original licenses and notices.

Sources: [Python 3.14.10](https://www.python.org/downloads/release/python-31410/), [Platform Tools](https://developer.android.com/tools/releases/platform-tools), [Frida Android](https://frida.re/docs/android/), [frida-tools metadata](https://pypi.org/pypi/frida-tools/14.13.1/json), [mitmproxy metadata](https://pypi.org/pypi/mitmproxy/12.3.1/json), [uv installation](https://docs.astral.sh/uv/getting-started/installation/), [uv Python management](https://docs.astral.sh/uv/guides/install-python/), [WireGuard Android](https://download.wireguard.com/android-client/). See [platforms](PLATFORMS.en.md) and [third-party notices](THIRD_PARTY_NOTICES.en.md).
