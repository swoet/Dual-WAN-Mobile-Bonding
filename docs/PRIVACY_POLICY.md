# Privacy Policy (Draft)

By default, the app:
- Does not store personal data or traffic logs on-device or server-side.
- Maintains aggregated counters (bytes, RTT, loss) for user display.
- Sends telemetry only if explicitly opted-in by the user.

When the optional bonding server is enabled:
- Traffic may be decrypted at the server if the target application uses plaintext protocols.
- Strongly recommend end-to-end encryption (HTTPS/TLS) for sensitive applications.
- Server stores only transient in-memory connection state; no persistent logs by default.

You can disable telemetry at any time in Settings.
