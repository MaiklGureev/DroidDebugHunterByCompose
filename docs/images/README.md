# UI screenshots

**English** | [Русский](README.ru.md)

These are actual window captures of DroidDebugHunter Compose 1.1.3 on Windows, taken on 2026-11-27 in the dark theme. English (`.en.jpg`) and Russian (`.ru.jpg`) images use the same application and demo data. The original JPEG captures are kept without image editing.

| Screen | English | Русский |
| --- | --- | --- |
| Traffic | [Image](traffic.en.jpg) | [Изображение](traffic.ru.jpg) |
| Response details | [Image](request-details.en.jpg) | [Изображение](request-details.ru.jpg) |
| Tool setup | [Image](tool-setup.en.jpg) | [Изображение](tool-setup.ru.jpg) |
| Markdown help | [Image](markdown-help.en.jpg) | [Изображение](markdown-help.ru.jpg) |

Traffic comes from [examples/ui-demo.har](../../examples/ui-demo.har): twelve synthetic requests to reserved `example.test` domains. Package names, app IDs, timestamps and payloads are fictional. No real Android device, credentials or captured network traffic appear in the images. The screenshot session used an isolated data directory and a read-only ADB fixture returning an empty device list.

To refresh the images, use a separate application data directory (`-Ddroiddebughunter.dataDir=...`) and disable automatic device refresh. Import the demo HAR, open the first request's Response tab, show Settings → Guided setup → Frida bundle, and open Help → ADB tools. Capture both interface languages. Do not start capture, install tools or expose personal paths/device identifiers for documentation screenshots.
