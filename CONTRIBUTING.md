# Contributing to Dual-WAN Mobile Bonding

Thank you for your interest! Maintained by [Shawn Mutogo](https://github.com/swoet).

## Project Architecture

- `app/android/` — Android app (Kotlin, Gradle, VPNService)
- `server/` — Optional bonding server (Go)
- `.github/workflows/` — CI: Android build, server build, integration tests

## Development Workflow

This project uses a **PR-first workflow**. No direct pushes to `main`.

1. Fork and clone.
2. Create a branch from `main`:
   ```bash
   git checkout -b feature/your-change
   ```
3. For Android development: install JDK 17 + Android SDK.
4. Build the Android app:
   ```bash
   cd app/android
   ./gradlew assembleDebug
   ```
5. Open a Pull Request against `main`.

## Commit Format

```
feat(android): add path-bias scheduler UI
fix(server): handle dropped tunnel connection gracefully
test(android): add RTT monitor unit tests
docs: update VPN architecture diagram
ci: enable integration test workflow
```

## CI

Android builds are gated by the presence of `app/android/.ci_ready`.
Server builds run on every PR. See `.github/workflows/` for details.

## Reporting Issues

Search [existing issues](https://github.com/swoet/Dual-WAN-Mobile-Bonding/issues) first, then open a new one with full reproduction steps.