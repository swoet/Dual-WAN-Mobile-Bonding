# Architecture Overview

This document summarizes the high-level system architecture for Dual‑WAN Mobile Bonding.

## Overview
- Android app (Kotlin) implements a VpnService to intercept and steer traffic.
- Two outbound paths: Wi‑Fi and Mobile data. Sockets are explicitly bound per-network via ConnectivityManager.
- Optional Bonding Server (Go) enables single-connection aggregation by reassembling subflows.

## Components
- UI: onboarding, interface chooser, per-app routing, stats screen, logs
- Core: VpnService, packet parsing, flow scheduler, health monitoring
- Network: socket binding (Network.bindSocket/openConnection), TLS 1.3/QUIC tunnels
- Server (optional): TLS listener, two client tunnels per device, NAT/conntrack, reordering and forwarding
- Infra/CI: Docker, VPS deploy scripts, GitHub Actions

## Data Flow
1. VpnService captures packets into a tun interface
2. PacketParser forms Flow descriptors (src/dst/ports/proto)
3. FlowScheduler decides MAIN vs HELPER (default 85/15) and handles failover
4. With server enabled, frames are sent over two secure tunnels; server reassembles and forwards

## Risks & Considerations
- Android VPN constraints (background behavior, OEM differences)
- Accurate per-network binding and permission handling
- TLS termination risk on server; recommend E2E TLS by apps when possible
- Helper data consumption budget management and ROC of dynamic policy
