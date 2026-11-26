# Viewing and saving bodies

**English** | [Русский](BODY_VIEWER.md)

On Traffic, double-click a request, press Enter or choose Details (`Детали`). A separate window contains Request, Response, cURL and available diagnostics. Multiple windows support comparison and receive subsequent events for their request. Esc closes a window.

| View | Output |
| --- | --- |
| Auto | Preview a recognized image; otherwise text/JSON formatting when possible. |
| Text | Text representation of captured bytes; binary data may be unreadable. |
| JSON | Pretty-print valid JSON; text remains available when parsing fails. |
| HTML text | Strip scripts/styles and tags without browser layout, JavaScript or network requests. |
| Image | Preview supported images; save damaged/unsupported data or inspect it as Base64/HEX. |
| HEX | Offsets, hexadecimal bytes and printable ASCII. |
| Base64 | Captured bytes encoded as Base64. |

Image previews have size limits; saved bytes are unchanged. For example, `iVBORw0KGgo` is a typical Base64 PNG prefix: use Image/Auto rather than interpreting the decoded image as UTF-8.

Save body writes the exact available bytes (decoding Base64); text bodies use UTF-8. Save view writes displayed text. Switching views does not change the source body or cURL. Copy view copies text or a supported image.

Incomplete/unavailable bodies are explicitly marked; partial-body filenames include `.partial`. Saving cannot recover missing bytes. cURL is rejected for an incomplete request body to avoid replaying a different request.

Choose PowerShell or POSIX format on the cURL tab. Review URL, cookies, headers and body before executing/sharing a command. See [usage](USAGE.en.md).
