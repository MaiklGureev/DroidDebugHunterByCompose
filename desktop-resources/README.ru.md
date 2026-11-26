# Нативные ресурсы

[English](README.md) | **Русский**

При упаковке Compose берёт ресурсы из `common/`, каталога ОС и каталога ОС/CPU.

Храните утилиты в `<os>-<arch>/tools/`: например, `windows-x64/tools/`, `macos-arm64/tools/`, `macos-x64/tools/`, `linux-x64/tools/`. Они исключены из Git. Запускайте `python3 prepare-tools.py --action download` на целевой машине (Windows: `python prepare-tools.py`). Интерпретатор, wheels и ADB должны соответствовать ОС/CPU. Android frida-server/Gadget выбираются отдельно по ABI телефона/приложения.

Не помещайте Windows-бинарники в `common/`: общие ресурсы попадут также в Linux/macOS. Для сборки и HAR-анализа комплект не обязателен. Нативные приложения включают собственный Java runtime и не требуют системного JDK/JRE.

Исходный репозиторий не включает бинарные дистрибутивы утилит. Перед передачей комплекта см. [уведомления сторонних компонентов](../THIRD_PARTY_NOTICES.ru.md). Собственный код проекта — [MIT](../LICENSE).
