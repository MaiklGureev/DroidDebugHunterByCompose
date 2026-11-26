# DroidDebugHunter · Compose

[English](README.md) | **Русский**

Desktop-приложение на **Kotlin + Compose Desktop + coroutines** для Android: устройства и ADB, управление Frida, анализ HTTP(S) и потоковый Logcat. Перенос функциональности [DroidDebugHunter](https://github.com/MaiklGureev/DroidDebugHunter) с WPF на Compose.

Настройка и нативная сборка предусмотрены для **Windows, macOS и Linux**. ОС/CPU определяются автоматически; пути к ADB и Python сохраняются отдельно для каждой платформы. Интерфейс, ADB, HAR и Logcat написаны на Kotlin; Python/JS-движки Frida и mitmproxy включены в исходники. См. [настройку платформ](docs/PLATFORMS.ru.md).

**Готовая Windows-сборка запускается без установленного JDK/JRE:** Java runtime включён в папку приложения. JDK 17 требуется только для разработки и сборки из исходников. macOS/Linux-поставки также содержат собственный runtime; их нужно собирать на соответствующей ОС.

Документация: [оглавление](docs/README.ru.md), [руководство](src/main/resources/runtime/help/USAGE.md), [настройка Android](src/main/resources/runtime/help/ANDROID_SETUP_GUIDE.md).

## Возможности

- USB, эмуляторы, Wi-Fi ADB, сопряжение Android 11+, автообновление устройств и сохранение выбора.
- Приложения: названия, пакеты, UID/App ID, сторонние/системные, Android-профили, запуск/остановка/перезапуск, JDWP, сведения и APK-пути, runtime-разрешения, очистка данных и удаление с подтверждением.
- frida-server: каталог четырёх ABI, проверка ELF и версии, установка, запуск с root и остановка собственного процесса. Для debuggable APK — сценарий Gadget/run-as.
- Четыре режима захвата: HTTP(S)-прокси через ADB reverse, Frida OkHttp, Frida Native TLS, VPN WireGuard. Пошаговая настройка, проверка готовности, CA и сохранённые VPN-профили.
- Импорт **и экспорт HAR** без телефона, фильтры запросов, сортировка, отдельные окна деталей с живыми обновлениями, cURL для PowerShell 7.3+ и sh/bash/zsh.
- Тела: Auto, Text, JSON, HTML-текст, Image, HEX, Base64. Копирование текста/изображения, сохранение точных исходных байтов и текста просмотра. Частичные тела отмечаются явно.
- Logcat: система или выбранный UID, буферы/история, фильтры Regex/tag/уровень/PID/TID/UID, несколько выбранных строк, автопрокрутка, отдельные окна, TXT/CSV/JSONL из снимка.
- Сеть и диагностика Android, ADB push/pull, установка APK, отдельные окна результатов.
- Тёмная и светлая темы, палитра команд, отмена операций и журнал ошибок.

## Запуск из исходников

Готовые установщики и переносимые архивы: [GitHub Releases](https://github.com/MaiklGureev/DroidDebugHunterByCompose/releases). Выберите Windows x64, macOS Apple Silicon/Intel или Linux x64. Java включена в приложение; инструкция первого запуска находится в релизе и архивах.

Нужен **JDK 17**; Gradle wrapper включён. JDK 21 можно использовать для запуска Gradle при наличии toolchain 17. В IntelliJ IDEA выберите JDK 17 для проекта и Gradle JVM, затем конфигурацию `desktop`.

```powershell
.\run.ps1
# либо
.\run.bat
```

Скрипт выбирает установленный JDK 17/21, не меняя глобальную настройку Java. Прямой запуск с настроенным `JAVA_HOME`:

```powershell
.\gradlew.bat run
```

Первой сборке нужен доступ к Maven/Gradle. Для HAR, интерфейса и unit-тестов внешние Android/Python-утилиты не требуются.

На macOS/Linux: `./gradlew run`. На macOS сочетания с ⌘ поддерживаются вместе с Ctrl.

Gradle-задача `run` запускает отдельную временную копию JAR. Повторная сборка проекта не заменяет классы уже работающего процесса; изменения исходников применяются после перезапуска приложения. В IDEA используйте конфигурацию `desktop`. При запуске `MainKt` напрямую из IDE остановите приложение перед Rebuild/Clean, иначе возможно `NoClassDefFoundError` для ещё не загруженных классов корутин.

## Локальный комплект утилит

Большие бинарные файлы **исключены из Git**. По умолчанию комплект берётся из `desktop-resources/<os>-<arch>/tools/` (например, `windows-x64`, `macos-arm64`, `linux-x64`) или из ресурсов готового приложения. Можно выбрать другой каталог и собственные ADB/Python, включая Python из venv. Автопоиск использует Android SDK и PATH. Комплекты с нативными бинарниками подготавливаются отдельно на каждой ОС/CPU.

Подготовка нового комплекта требует Python 3.14 для целевой ОС/CPU и выполняется явно. Общий скрипт для всех платформ (на Windows используйте `python`, на macOS/Linux — `python3`):

```sh
python3 prepare-tools.py --action init
python3 prepare-tools.py --action download --component all
python3 prepare-tools.py --action check
```

Windows-обёртка с дополнительной возможностью копирования установленного Python:

```powershell
.\prepare-tools.ps1 -Action init
.\prepare-tools.ps1 -Action download -Component all -Python python
.\prepare-tools.ps1 -Action check
```

Для переноса уже подготовленного комплекта из предыдущего проекта:

```powershell
.\prepare-tools.ps1 -Source 'D:\FridaDebugTool' -Action check
```

Флаг `-CopyPython` дополнительно копирует установленный Python 3.14 x64 в комплект. Для захвата используются зафиксированные версии из `capture-requirements.txt` и `proxy-requirements.txt`. Frida client/server/Gadget должны совпадать: **17.26.2**.

В интерфейсе доступны аналогичные проверка и подготовка утилит. Обычный запуск программы ничего не скачивает. Из старого проекта не копируются настройки, трафик, CA и VPN-ключи.

## Работа с телефоном

1. Включите USB-отладку и подтвердите RSA-ключ компьютера на Android.
2. Выберите устройство в верхней панели. Обновите приложения, выберите нужное.
3. На экране «Трафик» выберите режим и откройте настройку захвата значком рядом с запуском.
4. Выполните автоматические проверки и ручные шаги Android. Запустите захват.

Прокси не определяет пакет/UID; VPN указывает пакет по allowlist конфигурации. Для HTTPS через прокси/VPN приложение должно доверять CA. Pinning, недоступные TLS-функции и HTTP/3 могут ограничивать захват. Для Frida без root нужен debuggable APK с доступным `run-as`.

Полное руководство встроено в раздел «Справка». HAR можно открыть сразу без Android.

## Клавиатура

| Действие | Клавиши |
| --- | --- |
| Импорт HAR | Ctrl+O |
| Палитра команд | Ctrl+K |
| Справка | F1 |
| Детали запроса/строки Logcat | Enter или двойной щелчок |
| Несколько строк Logcat | Ctrl/Shift + щелчок |
| Копировать выбранные строки Logcat | Ctrl+C |
| Закрыть окно результата/деталей | Esc |

## Сессии и память

Переходы между экранами сохраняют захват. При смене/потере устройства потоки останавливаются. Закрытие приложения отменяет собственные операции, завершает процессы, восстанавливает прежний прокси и удаляет принадлежащие сессии ADB forward/reverse. Общий ADB-сервер при выходе не завершается. После отключения телефона или принудительного завершения приложения восстановление Android-настроек может потребоваться вручную.

Настройки, CA и VPN-ключи хранятся в пользовательском каталоге данных: `%LOCALAPPDATA%\DroidDebugHunterCompose` на Windows, `~/Library/Application Support/DroidDebugHunterCompose` на macOS, `$XDG_DATA_HOME/DroidDebugHunterCompose` либо `~/.local/share/DroidDebugHunterCompose` на Linux. Сброс CA/VPN переносит файлы в резервную папку. Установленные CA и импортированные профили автоматически с телефона не удаляются. Для остановки VPN-туннеля через broadcast требуется Remote control в WireGuard.

Помимо ограничения числа записей действуют бюджеты приблизительного объёма: 96 МиБ для HTTP и 64 МиБ для Logcat. Старые записи вытесняются; счётчики видны в интерфейсе. Очередь Logcat ограничена, пропуски при перегрузке учитываются. Regex использует RE2/J: поиск выполняется за линейное время, конструкции lookbehind/backreference не поддерживаются.

## Проверки и сборка

```powershell
.\run.ps1 -Task test
.\run.ps1 -Task createDistributable
```

Переносимая Windows-папка: `build/compose/binaries-1.1.1/main/app/DroidDebugHunterByCompose/`. Она включает собственный JRE и локальные утилиты, если они подготовлены. Копируйте всю папку вместе с `app/` и `runtime/`. Для MSI: `-Task packageMsi`. Compose-плагин подготавливает WiX; при первой упаковке нужен доступ к его загрузке на GitHub. Для macOS/Linux: `./gradlew test createDistributable`, затем при необходимости `packageDmg`/`packageDeb` на целевой ОС.

Проверка запуска Windows без системной Java: `./scripts/smoke_windows.ps1 -ApplicationRoot ./build/compose/binaries-1.1.1/main/app/DroidDebugHunterByCompose`. Скрипт отключает пути системной Java для дочернего процесса, проверяет встроенный runtime, выбор устройства и завершение модели приложения. Проверка работает с временными данными и не подключается к Android. На macOS/Linux: `python3 scripts/package_release.py --runtime-check`.

Тесты проверяют HAR, бинарные тела и частичные данные, cURL в двух shell-форматах, профили ОС/CPU, обнаружение утилит, ADB/UID/разрешения, Logcat, тайм-ауты и отмену процессов. CI настроен на Windows, Ubuntu и macOS: тесты, сборка нативного образа, отчёты и артефакты. Offline-проверки подготовки: `python -m unittest discover -s scripts -p 'test_*.py'`. Полные Frida/VPN-сценарии требуют отдельной проверки на совместимом Android APK.

При наличии локального комплекта дополнительно запускается интеграционный тест прокси с HTTP-сервером на loopback: он проверяет бинарные запрос и ответ без обращения к Android или внешнему сайту. Без комплекта этот тест пропускается. Android DEX-помощник поставляется вместе с `AppLabels.java`; для его пересборки используйте `python scripts/build_app_labels.py --sdk <Android-SDK> --java-home <JDK>`.

## Участие и публикация

См. [CONTRIBUTING.md](CONTRIBUTING.ru.md), [SECURITY.md](SECURITY.ru.md), [архитектуру](docs/ARCHITECTURE.ru.md) и [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.ru.md).

Собственный код проекта распространяется по [лицензии MIT](LICENSE). Сторонние компоненты сохраняют свои лицензии и уведомления; их бинарные комплекты не входят в исходный Git-репозиторий.
