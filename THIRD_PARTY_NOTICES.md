# Third-party notices

**English** | [Русский](THIRD_PARTY_NOTICES.ru.md)

This document lists component sources and notice locations. The project's own code is licensed under [MIT](LICENSE); that does not change the licenses of third-party components. The repository contains Kotlin code, project-owned Python/JS agents, an Android helper and preparation scripts ported from DroidDebugHunter. Downloaded binary tool bundles are excluded from Git.

| Component | Use / source |
| --- | --- |
| Kotlin, coroutines | JVM and asynchronous work: [Kotlin](https://github.com/JetBrains/kotlin), [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| Compose Multiplatform / AndroidX / Skiko | UI and graphics: [Compose](https://github.com/JetBrains/compose-multiplatform), [AndroidX](https://android.googlesource.com/platform/frameworks/support/), [Skiko](https://github.com/JetBrains/skiko) |
| Gson | JSON/HAR: [google/gson](https://github.com/google/gson) |
| RE2/J | Logcat filters: [google/re2j](https://github.com/google/re2j) |
| Gradle wrapper | Build: [Gradle](https://github.com/gradle/gradle) |
| JUnit | Tests only: [JUnit](https://github.com/junit-team/junit5) |
| JDK / JRE | Installed by the developer; jpackage includes the selected JDK's runtime and notices |
| Android SDK Platform Tools | Local ADB and libraries: [official downloads](https://developer.android.com/tools/releases/platform-tools); retain `NOTICE.txt` and `source.properties` |
| Frida / Gadget 17.26.2 | Python bindings, servers and Gadget: [Frida](https://github.com/frida/frida) |
| frida-tools 14.13.1 | OkHttp agent REPL: [frida-tools](https://github.com/frida/frida-tools) |
| mitmproxy / mitmproxy_rs | Proxy and WireGuard server: [mitmproxy](https://github.com/mitmproxy/mitmproxy) |
| WireGuard Android | Optional APK: [source](https://git.zx2c4.com/wireguard-android/), [downloads](https://download.wireguard.com/android-client/) |
| Python and Python packages | Local runtime and pinned dependencies; retain `LICENSE.txt`, `*.dist-info` and license directories in the bundle |

JVM dependency versions are pinned in Gradle; Python versions are pinned in `src/main/resources/runtime/tool-setup/*-requirements.txt`. Transitive dependencies and notices are part of the actual installed packages. Binary distribution contents depend on the prepared tools bundle and selected JDK.

The source repository and a binary bundle with APK/SDK/Python have different contents. Before distributing a binary bundle, review the terms of the components actually included and retain required license texts, notices and materials. The application's MIT License does not replace downloaded tools' licenses.
