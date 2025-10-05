# Dual-WAN Mobile Bonding — PRD

- Version: 1.0
- Author: autonomous-ai-agent
- Date: 2025-10-05

## Stakeholders
- Product Owner: user
- Android Engineer: agent
- Backend Engineer: agent
- QA: agent
- DevOps: agent

## Problem statement
Mobile users often have multiple internet sources (mobile data and private Wi‑Fi). One may have a large data bundle but poor quality, while the other has smaller quota but better link quality. Users want a mobile-only software solution that:
1. Lets them select which network is "main" (the one to deplete preferentially).
2. Uses the helper link sparingly to improve reliability, reduce retransmits, and provide failover when main degrades.
3. Optionally aggregates bandwidth for single large downloads using an optional server-side component.

## Goals
- Primary: create an Android app that implements VPN-based steering and optional server bonding
- Secondary: minimize helper link consumption (<15% typical in normal conditions), fast failover (<1s), secure tunnels

## Success metrics
- App routes >85% bytes through main when both links are available (measured by integration tests)
- Failover latency under 1s in simulated drop tests
- Helper consumption ≤15% during normal streaming tests
- Single large-file throughput improvement >1.5x when optional server bonding is enabled and two links have similar capacity

## Non-goals
- Toll-free helper use (cannot make helper carry zero bytes while "helping")
- Support for iOS in v1

## User stories
- As a user I can select main and helper interfaces in-app.
- As a user I can enable "per-app routing" so a chosen app drains my main bundle.
- As a user I can enable "bonding server" if I want single-connection aggregation.
- As a user I can see live stats: bytes used by each interface, latency, packet loss.

## Acceptance criteria
- App has end-to-end flow: install → onboarding explaining permissions → choose interfaces → start VPN → browsing + measured bias towards main interface.
- Automated tests demonstrate goals and metrics.

## Privacy & security
- All traffic is encrypted by default to the bonding server using TLS 1.3 (when enabled).
- User data/logs stored only if user opts in. Default logs: only aggregated counters.
- Provide a privacy policy markdown file in repo.

## Regulatory
- Must not break carrier TOS intentionally. Add warning in onboarding.

---

## Architecture (high-level)
Android app (Kotlin) implements a VpnService to intercept device traffic. The VPN endpoint packages flows and forwards them over two secure tunnels bound to Wi‑Fi and Mobile network sockets respectively.

- Option A (no-bond-server): per-flow steering and failover on device, no true single-connection aggregation.
- Option B (with-bond-server): client splits traffic into subflows to a cloud bonding server (Go) that reorders/forwards, enabling true bonding.

### Components
- Android app (Kotlin): UI, VpnService, packet queue, scheduler, health monitor
- Network drivers: two outbound sockets per-network (Wi‑Fi/Mobile)
- Encryption: TLS 1.3 or QUIC (experimental), optional cert pinning
- Telemetry: optional aggregated metrics
- Bonding Server (Go, optional): accepts subflows, reassembles and forwards, NAT/conntrack
- DevInfra: Dockerfiles, VPS deploy scripts, GitHub Actions CI

### Data flow
On device: packets → flows → scheduler (85/15 bias) → frames → per-interface tunnel or server → responses reverse path.

---

## Tech stack & frameworks
- Android: Kotlin (1.9+), targetSdk 33, minSdk 28, Gradle (Kotlin DSL)
  - Libraries: core-ktx, lifecycle-ktx, coroutines, okhttp, serialization, packet utils
  - APIs: VpnService, ConnectivityManager, Network.bindSocket/openConnection
- Backend (optional): Go 1.21+, net/http, goroutines + channels, TLS 1.3
- Infra: Ubuntu 22.04 VPS, Docker/Compose, Systemd, Certbot (Let’s Encrypt)
- CI/CD: GitHub Actions (android build/test, server build, integration tests)
- Testing: junit, espresso, robolectric, Linux netns for link simulation

---

## Milestones
- M0: PRD & repo scaffold (this doc, LICENSE, README)
- M1: Core VPN skeleton + UI (minimal packet capture, unit tests)
- M2: Per-flow steering + interface binding (ConnectivityManager, socket binding)
- M3: Helper bias scheduler + metrics (85/15 default, failover)
- M4: Optional Bonding Server (Go + Docker)
- M5: E2E tests + CI (link drop, failover, helper consumption)
- M6: Release (signed APKs, privacy policy, Play Store checklist)

---

## Scheduling policy (core logic)
Defaults: main_fraction=0.85, helper_fraction=0.15, min helper 5 MB/min baseline.
Dynamic adjustment: measure RTT/loss; increase helper share on degradation (cap 50%), decay on recovery.
Flow assignment: short flows prefer lower latency; large flows prefer main.
Failover: if main unresponsive 500ms → move flows to helper within <1s.

---

## Security & privacy details
- TLS 1.3 for control/data plane to server; optional QUIC
- Token-based client auth; rotate on reconnect
- Minimal server state; logs opt-in only
- Let’s Encrypt automation scripts; self-hosted certs supported
- Permissions: VpnService, INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE, (bind network as needed)
- Threat model: server sees decrypted traffic if termination occurs; encourage E2E TLS

---

## CI/CD and testing
- GitHub Actions: android-build, server-build, integration-tests
- Tests: unit (80% coverage target for core), integration (failover, helper use), e2e (espresso UI)
- Release policy: PRs must pass checks; GitHub Release with APKs and image tags

---

## Acceptance tests
- AT1 Main bias validation (>=85% main traffic)
- AT2 Failover (<1s, helper >95% traffic during outage)
- AT3 Helper minimal consumption (<=15% typical)
- AT4 Bonding aggregation (option enabled)
