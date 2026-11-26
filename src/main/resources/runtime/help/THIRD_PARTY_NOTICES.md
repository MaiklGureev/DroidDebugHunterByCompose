# Сторонние компоненты

Этот файл перечисляет источники компонентов и места их уведомлений. Он не назначает лицензию собственному коду проекта. В репозитории находятся исходники Kotlin, собственные агенты Python/JS, Android-помощник и скрипты подготовки из предыдущего DroidDebugHunter. Загруженные бинарные комплекты исключены из Git.

| Компонент | Использование / источник |
| --- | --- |
| Kotlin, coroutines | JVM и асинхронная работа: [Kotlin](https://github.com/JetBrains/kotlin), [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| Compose Multiplatform / AndroidX / Skiko | Интерфейс и графика: [Compose](https://github.com/JetBrains/compose-multiplatform), [AndroidX](https://android.googlesource.com/platform/frameworks/support/), [Skiko](https://github.com/JetBrains/skiko) |
| Gson | JSON/HAR: [google/gson](https://github.com/google/gson) |
| RE2/J | Фильтры Logcat: [google/re2j](https://github.com/google/re2j) |
| Gradle wrapper | Сборка: [Gradle](https://github.com/gradle/gradle) |
| JUnit | Только тесты: [JUnit](https://github.com/junit-team/junit5) |
| JDK / JRE | Устанавливается разработчиком; jpackage включает runtime выбранного JDK и его уведомления |
| Android SDK Platform Tools | Локальный ADB и DLL: [официальные загрузки](https://developer.android.com/tools/releases/platform-tools); сохраняются `NOTICE.txt` и `source.properties` |
| Frida / Gadget 17.26.2 | Python bindings, серверы и Gadget: [Frida](https://github.com/frida/frida) |
| frida-tools 14.13.1 | REPL для OkHttp-агента: [frida-tools](https://github.com/frida/frida-tools) |
| mitmproxy / mitmproxy_rs | Прокси и WireGuard-сервер: [mitmproxy](https://github.com/mitmproxy/mitmproxy) |
| WireGuard Android | Опциональный APK: [исходники](https://git.zx2c4.com/wireguard-android/), [загрузки](https://download.wireguard.com/android-client/) |
| Python и Python-пакеты | Локальный runtime и зафиксированные зависимости; `LICENSE.txt`, `*.dist-info` и каталоги лицензий сохраняются в комплекте |

Версии JVM-библиотек закреплены в Gradle; версии Python-пакетов — в `src/main/resources/runtime/tool-setup/*-requirements.txt`. Их транзитивные зависимости и уведомления входят в фактические пакеты. Полный состав бинарной поставки зависит от подготовленного локального комплекта и выбранного JDK.

Исходный репозиторий и готовый комплект с APK/SDK/Python — разные составы поставки. Перед публичным выпуском бинарного комплекта владелец проверяет условия фактически включённых компонентов и включает требуемые тексты лицензий, уведомления и материалы. Лицензии загруженных инструментов не заменяются будущей лицензией приложения.
