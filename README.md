# Dual-WAN Mobile Bonding (Android-first)

A production-ready project to build an Android app that intelligently uses both Wi‑Fi and mobile data simultaneously via a VPNService, with optional true bandwidth aggregation using a cloud bonding server.

Key features:
- Select a main connection (larger data bundle) and a helper connection (smaller bundle)
- Bias traffic so the main carries most bytes while the helper improves stability (failover, retransmits, latency reduction)
- Optional single-connection bonding via a server component (Go)
- Per-app routing (Android) to respect user privacy and control data usage
- CI/CD pipelines, infra scripts for a VPS deployment, and comprehensive documentation

Project status:
- Milestone M0: PRD and repository scaffold.
- Milestone M1: Android VPN skeleton + UI committed.
- Milestone M2: Network selection and RTT monitoring committed.
- Milestone M3: Helper-bias scheduler (range-based test download) committed.
- Next: M4+ server integration paths and bonding mode (optional), tests, and release artifacts.

Repository structure:
- docs/: PRD, architecture, privacy policy, and checklists
- app/android/: Android app (Kotlin, Gradle)
- server/: Optional bonding server (Go)
- scripts/: Dev and deployment scripts
- infra/: VPS deployment helpers
- .github/workflows/: CI pipelines

Quickstart (dev):
- Recommended OS: Linux (Ubuntu 22.04) or macOS. Windows users can use WSL2 for server/CI tasks.
- Install: JDK 17, Android SDK cmdline tools, Docker, Go 1.21+

Android app (once implemented):
- cd app/android
- ./gradlew assembleDebug
- Install on device/emulator via Android Studio or adb

Optional server (once implemented):
- cd server
- docker build -t bonding-server:local .
- docker run -p 443:443 bonding-server:local

CI: See .github/workflows for android-build, server-build, and integration-tests.

License: MIT
