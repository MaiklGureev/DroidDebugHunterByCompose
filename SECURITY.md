# Reporting security issues

**English** | [Русский](SECURITY.ru.md)

Do not post real tokens, cookies, request bodies, device logs, certificates with private keys or WireGuard profiles in issues/PRs. Use synthetic data for reproduction.

For a vulnerability, use the repository's private GitHub Security Advisories channel if enabled, or contact the owner before disclosing technical details publicly. Include the version, reproduction scenario, impact and a minimal sanitized example.

The application processes traffic and controls the selected Android device. CA certificates, VPN keys and settings are stored in the user's local data directory and must not be published in the source repository. Tool preparation/downloads run only on an explicit user action.
