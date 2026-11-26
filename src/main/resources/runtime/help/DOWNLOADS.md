# Утилиты и загрузки

[English](DOWNLOADS.en.md) | **Русский**

Java включена в нативную сборку. HAR работает без внешних утилит. Для телефона нужен ADB; для захвата — Python 3.14 и компоненты режима. Бинарные комплекты исключены из Git и скачиваются только по явной команде.

## Подготовка

В «Настройки → Инструменты» выберите каталог с правом записи, ADB/Python, примените настройки, выберите компонент и выполните проверку или «Скачать / подготовить». Либо из корня репозитория:

```sh
python3 prepare-tools.py --action init
python3 prepare-tools.py --action download --component all
python3 prepare-tools.py --action check
```

На Windows используйте `python`, либо `prepare-tools.ps1`. Ключи: `--root <каталог>`, `--component adb|server|gadget|capture|proxy|wireguard|all`, `--offline`. Пакеты устанавливаются через `pip --target` в комплект, а не глобально. `--offline` запрещает сетевые загрузки; недостающие компоненты потребуется подготовить заранее.

## Состав

| Компонент | Каталог / назначение |
| --- | --- |
| `adb` | `platform-tools/`: нативный ADB и его библиотеки/NOTICE. |
| `server` | `frida-server/`: распакованные Android ELF для arm, arm64, x86, x86_64. |
| `gadget` | `frida-gadget/`: распакованные `.so` соответствующих ABI. |
| `capture` | `frida-python/`: Frida 17.26.2 и frida-tools с зависимостями. |
| `proxy` | `proxy-python/`: mitmproxy/mitmproxy_rs и зависимости HTTP/VPN. |
| `wireguard` | `android-tools/`: опциональный WireGuard APK. |

Python-интерпретатор задаётся отдельно, включая venv. Ожидается Python 3.14 целевой ОС/CPU. Версии Python-пакетов закреплены в `capture-requirements.txt` и `proxy-requirements.txt`; наличие нативных wheels зависит от платформы. Frida client/server/Gadget должны совпадать: 17.26.2.

Windows `.exe`/wheels не подходят для macOS/Linux. Android ABI приложения может отличаться от устройства. Нужны распакованные ELF/`.so`, а не `.xz` и не Windows Frida Devkit. Сохраняйте весь комплект с зависимостями и лицензиями.

Источники: [Platform Tools](https://developer.android.com/tools/releases/platform-tools), [Python](https://www.python.org/downloads/), [Frida 17.26.2](https://github.com/frida/frida/releases/tag/17.26.2), [WireGuard Android](https://download.wireguard.com/android-client/). Автоматическая подготовка использует зафиксированные URL/версии проекта. [Платформы](PLATFORMS.md), [сторонние уведомления](THIRD_PARTY_NOTICES.md).
