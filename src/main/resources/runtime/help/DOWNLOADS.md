# Где скачать инструменты и куда положить

Стандартные каталоги создаются автоматически. В приложении откройте **Справка → Утилиты Windows**; из исходников доступны `setup-tools.bat` и `download-tools.bat`. [Инструкция по настройке](../docs/TOOL_SETUP.md).

Эта инструкция лежит рядом с `DroidDebugHunter.csproj`. Все пути в таблице ниже указаны **относительно этой папки приложения**. Загруженные инструменты, установщики, Python-пакеты и архивы исключены из Git; локально они продолжают использоваться программой.

## Подготовить всё автоматически

В полном проекте перейдите в корень решения — папку с `DroidDebugHunter.slnx`, `tools` и `README.md`. Установите Python 3.14 x64 и выполните:

```powershell
.\tools\prepare_tools.ps1 -Python python
dotnet build .\DroidDebugHunter.slnx -c Release
```

Скрипт создаёт папки ниже сам. Если архив ADB уже скачан:

```powershell
.\tools\prepare_tools.ps1 -Python python -PlatformToolsArchive .\platform-tools_r37.0.1-windows.zip
```

Для публикации полного проекта нужен корень **решения**, включая `tools`, `docs`, `DroidDebugHunter.Checks` и `ANDROID_SETUP_GUIDE.md`. Одна папка с `.csproj` не заменяет весь репозиторий: проект ссылается и на файлы уровнем выше.

## Ссылки и назначение папок

| Что скачать | Официальный источник | Куда установить или распаковать |
| --- | --- | --- |
| Android SDK Platform Tools для Windows | [Windows ZIP](https://dl.google.com/android/repository/platform-tools-latest-windows.zip), [страница Google](https://developer.android.com/tools/releases/platform-tools) | `platform-tools/`: рядом должны лежать `adb.exe`, `AdbWinApi.dll`, `AdbWinUsbApi.dll`, `NOTICE.txt`, `source.properties`. |
| frida-server 17.26.2 для Android | [Релиз и Assets](https://github.com/frida/frida/releases/tag/17.26.2), прямые ссылки ниже | Распакованные файлы без `.xz` в `frida-server/`. Имена не меняйте. |
| Frida Gadget 17.26.2 для Android | [Тот же релиз и Assets](https://github.com/frida/frida/releases/tag/17.26.2), прямые ссылки ниже | Распакованные `.so` в `frida-gadget/`. Нужен для Frida без root в debuggable APK. |
| WireGuard Android 1.0.20260315 | [Официальный APK](https://download.wireguard.com/android-client/com.wireguard.android-1.0.20260315.apk), [страница загрузки](https://download.wireguard.com/android-client/) | `android-tools/com.wireguard.android-1.0.20260315.apk`. Это файл для кнопки «Подготовить VPN». |
| Python bindings Frida и frida-tools | [frida 17.26.2](https://pypi.org/project/frida/17.26.2/), [frida-tools 14.13.1](https://pypi.org/project/frida-tools/14.13.1/) | Установить через `tools/prepare_capture.ps1` в `frida-python/`, а не просто положить сюда wheel/архив. |
| mitmproxy и зависимости прокси/VPN | [mitmproxy 12.3.1](https://pypi.org/project/mitmproxy/12.3.1/), версии в `tools/proxy-requirements.txt` полного проекта | Установить через `tools/prepare_proxy.ps1` в `proxy-python/`. Используйте Python 3.14 x64 для подготовленного комплекта. |
| Python 3.14 x64 | [Windows-загрузки Python](https://www.python.org/downloads/windows/) — выберите ветку 3.14 и 64-bit installer | Установить в Windows. В программе при необходимости укажите полный путь к `python.exe`. Установщик в Git не добавляется. |
| .NET 10 SDK / Desktop Runtime x64 | [Загрузки .NET 10](https://dotnet.microsoft.com/en-us/download/dotnet/10.0) | SDK установить в Windows для сборки; Windows Desktop Runtime нужен для запуска framework-dependent сборки. В Git не добавляется. |
| Windows Core Devkit Frida, необязательно | [Assets релиза 17.26.2](https://github.com/frida/frida/releases/tag/17.26.2), файл `frida-core-devkit-17.26.2-windows-x86_64.exe` | Если нужен для отдельной native-разработки — распаковать в `frida/`. Для сборки текущего C#-приложения этот Devkit не требуется. |

ZIP Platform Tools уже содержит каталог `platform-tools`: извлекайте его в папку приложения так, чтобы не получилось `platform-tools/platform-tools/adb.exe`. Ссылка Google с `latest` может выдавать новую версию; имя старого локального ZIP не является ссылкой на доступный старый релиз.

## Frida: ссылки для каждой архитектуры

Скачайте все четыре архитектуры, если программа должна выбирать автоматически для разных устройств. Для единственного устройства достаточно его архитектуры. Версии сервера, Gadget и Python-клиента должны совпадать — **17.26.2**.

| ABI Android | Архитектура | Архив сервера | Архив Gadget |
| --- | --- | --- | --- |
| `arm64-v8a` | `arm64` | [server arm64](https://github.com/frida/frida/releases/download/17.26.2/frida-server-17.26.2-android-arm64.xz) | [Gadget arm64](https://github.com/frida/frida/releases/download/17.26.2/frida-gadget-17.26.2-android-arm64.so.xz) |
| `armeabi-v7a` | `arm` | [server arm](https://github.com/frida/frida/releases/download/17.26.2/frida-server-17.26.2-android-arm.xz) | [Gadget arm](https://github.com/frida/frida/releases/download/17.26.2/frida-gadget-17.26.2-android-arm.so.xz) |
| `x86_64` | `x86_64` | [server x86_64](https://github.com/frida/frida/releases/download/17.26.2/frida-server-17.26.2-android-x86_64.xz) | [Gadget x86_64](https://github.com/frida/frida/releases/download/17.26.2/frida-gadget-17.26.2-android-x86_64.so.xz) |
| `x86` | `x86` | [server x86](https://github.com/frida/frida/releases/download/17.26.2/frida-server-17.26.2-android-x86.xz) | [Gadget x86](https://github.com/frida/frida/releases/download/17.26.2/frida-gadget-17.26.2-android-x86.so.xz) |

У сервера для Android нужен ELF-файл, а не Windows Devkit `.exe` и не оставшийся `.xz`. У Gadget нужен `.so`. Для debuggable-сценария ABI приложения может отличаться от ABI устройства, например 32-битное приложение на 64-битном Android.

Для распаковки вручную скачанных серверов средствами полного проекта:

```powershell
python .\tools\prepare_frida.py --unpack-only --archives .\downloads
```

Папку `downloads/` создайте в корне решения и положите в неё четыре серверных `.xz`. Этот режим проверяет ELF/ABI, но не обращается к GitHub за контрольными суммами. Онлайн-подготовка через `prepare_frida.py` и `prepare_extra_tools.py` сверяет доступные суммы релиза.

## Ожидаемые файлы после подготовки

Пути от папки с `DroidDebugHunter.csproj`:

```text
platform-tools/adb.exe
platform-tools/AdbWinApi.dll
platform-tools/AdbWinUsbApi.dll
platform-tools/NOTICE.txt
platform-tools/source.properties
frida-server/frida-server-17.26.2-android-arm64
frida-server/frida-server-17.26.2-android-arm
frida-server/frida-server-17.26.2-android-x86_64
frida-server/frida-server-17.26.2-android-x86
frida-gadget/frida-gadget-17.26.2-android-arm64.so
frida-gadget/frida-gadget-17.26.2-android-arm.so
frida-gadget/frida-gadget-17.26.2-android-x86_64.so
frida-gadget/frida-gadget-17.26.2-android-x86.so
android-tools/com.wireguard.android-1.0.20260315.apk
frida-python/frida/__init__.py
frida-python/frida_tools/__init__.py
proxy-python/mitmproxy/__init__.py
```

Это перечень ключевых файлов, а не полное содержимое Python-пакетов: сохраняйте все их зависимости и лицензионные файлы. После добавления инструментов пересоберите приложение; MSBuild копирует нужные файлы рядом с EXE. Для переноса офлайн копируется весь каталог сборки с подготовленными инструментами.

## Что остаётся только локально

`bin/`, `obj/`, инструменты, Python-пакеты, скачанные архивы, `runtime/`, HAR и ключи игнорируются Git. Правила не удаляют локальные файлы. Уже добавленные в индекс файлы необходимо снять с индекса через `git rm --cached` — обычное добавление шаблона в `.gitignore` не отменяет их отслеживание.
