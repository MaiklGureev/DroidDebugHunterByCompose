# DroidDebugHunter — первый запуск

Java уже включена в приложение. Устанавливать JDK/JRE в систему не требуется.

- Windows: распакуйте весь ZIP и запустите `DroidDebugHunterByCompose.exe`. Каталоги `app` и `runtime` должны оставаться рядом с exe. Либо используйте MSI.
- macOS: откройте `.app` из распакованной папки либо перенесите его из DMG в Applications. Архитектура arm64 — для Apple Silicon, x64 — для Intel. Сборка пока без подписи издателя и notarization.
- Linux x64: запустите `./bin/DroidDebugHunterByCompose` в распакованной папке либо установите DEB. Требуется графическая среда рабочего стола.

HAR-просмотр доступен сразу. Файл для знакомства: [examples/sample.har](https://github.com/MaiklGureev/DroidDebugHunterByCompose/blob/v1.1.0/examples/sample.har).

Для работы с телефоном откройте **Настройки → Инструменты** и выберите ADB, Python 3.14 и каталог локального комплекта. Кнопка подготовки скачивает дополнительные компоненты явно. Эти архивы не содержат отдельный Python, ADB, серверы Frida или APK WireGuard.

USB-отладку, доверие сертификатам и разрешение VPN подтверждают на Android. Для выбора режима и подготовки используйте значок настройки на экране «Трафик» и встроенную справку.

Релизы и SHA-256: https://github.com/MaiklGureev/DroidDebugHunterByCompose/releases

См. `THIRD_PARTY_NOTICES.md`. Лицензия собственного кода пока не выбрана владельцем.
