# Tools and downloads

**English** | [Русский](DOWNLOADS.md)

Native packages include Java. HAR needs no external tools. Devices require ADB; capture requires Python 3.14 and mode-specific components. Binary bundles are excluded from Git and downloaded only on explicit request.

## Preparation

In Settings → Tools (`Настройки → Инструменты`), select a writable directory and ADB/Python paths, apply settings, choose a component, then check or Download/prepare. Or run from the repository root:

```sh
python3 prepare-tools.py --action init
python3 prepare-tools.py --action download --component all
python3 prepare-tools.py --action check
```

On Windows, use `python` or `prepare-tools.ps1`. Options: `--root <directory>`, `--component adb|server|gadget|capture|proxy|wireguard|all`, `--offline`. Packages are installed into the bundle with `pip --target`, not globally. Offline mode forbids network downloads; prepare missing components beforehand.

## Contents

| Component | Directory / purpose |
| --- | --- |
| `adb` | `platform-tools/`: native ADB, libraries and NOTICE. |
| `server` | `frida-server/`: extracted Android ELF for arm, arm64, x86 and x86_64. |
| `gadget` | `frida-gadget/`: extracted `.so` for the corresponding ABIs. |
| `capture` | `frida-python/`: Frida 17.26.2, frida-tools and dependencies. |
| `proxy` | `proxy-python/`: mitmproxy/mitmproxy_rs and HTTP/VPN dependencies. |
| `wireguard` | `android-tools/`: optional WireGuard APK. |

Configure the Python interpreter separately, including a venv if desired. Python 3.14 must match host OS/CPU. Package versions are pinned in `capture-requirements.txt` and `proxy-requirements.txt`; native wheel availability depends on the platform. Frida client/server/Gadget must match 17.26.2.

Windows executables/wheels do not work as a macOS/Linux bundle. Android app ABI can differ from device ABI. Use extracted ELF/`.so`, not `.xz` archives or a Windows Frida Devkit. Retain the full bundle, dependencies and licenses.

Sources: [Platform Tools](https://developer.android.com/tools/releases/platform-tools), [Python](https://www.python.org/downloads/), [Frida 17.26.2](https://github.com/frida/frida/releases/tag/17.26.2), [WireGuard Android](https://download.wireguard.com/android-client/). Automatic preparation uses project-pinned URLs/versions. See [platforms](PLATFORMS.en.md) and [third-party notices](THIRD_PARTY_NOTICES.en.md).
