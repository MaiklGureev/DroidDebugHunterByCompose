# Native resources

**English** | [Русский](README.ru.md)

Native application resources are loaded from `common/`, the host OS directory and the host OS/CPU directory by Compose packaging.

Keep tooling in `<os>-<arch>/tools/`, for example `windows-x64/tools/`, `macos-arm64/tools/`, `macos-x64/tools/` or `linux-x64/tools/`. These folders are excluded from Git. Run `python3 prepare-tools.py --action download` on the target host (Windows: `python prepare-tools.py`). The Python interpreter, wheels and ADB must match the host OS/CPU. Android Frida servers/Gadget are selected separately by the phone's ABI.

Do not put Windows binaries in `common/`: common resources would be included in Linux/macOS distributions. Builds and HAR analysis do not require a tools bundle. Native distributions contain a private Java runtime; no system JDK/JRE is required for running them.

The source repository does not vendor their binary distributions. See [third-party notices](../THIRD_PARTY_NOTICES.md) before distributing a binary bundle. Project-owned code is under [MIT](../LICENSE).
