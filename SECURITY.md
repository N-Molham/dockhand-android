# Security Policy

## Reporting a vulnerability

Open a private security advisory: repository → Security → Advisories → "Report a vulnerability". Do not open a public issue for security problems.

Include: affected version (`Settings` screen shows version), Android version, device, reproduction steps, and impact. You should receive a response within 7 days.

## Do not paste secrets

Never include API tokens, server URLs, hostnames, IP addresses, keystore material or `.env` contents in issues, pull requests, screenshots or logs. Redact sensitive values before posting. Maintainers may edit or delete any content that exposes credentials.

## What the app stores

- API tokens and custom header values: encrypted with an Android Keystore AES-256-GCM key. Never written to logs, preferences in plaintext, or backups (Auto Backup is disabled).
- Server profiles, URLs and selected environment: DataStore preferences.
- No analytics, telemetry or third-party network calls. The app talks only to the Dockhand servers you configure.

Cleartext HTTP is disabled per profile by default; enabling it sends traffic, including tokens, unencrypted. Prefer HTTPS.

## Supported versions

Only the latest release receives security fixes. Check `version.properties` for the current version.
