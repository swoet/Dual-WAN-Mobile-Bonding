# Blockers and Technical Notes

This document records known gaps relative to the PRD and proposed paths to close them.

1) Device-wide TCP forwarding via VpnService
- Current app implements VPN skeleton but does not yet proxy TUN traffic back to networks. Building a user-space TCP/IP stack (tun2socks) is non-trivial.
- Options:
  a) Integrate an existing permissively-licensed tun2socks implementation (e.g., gVisor/lwip bindings) to translate TUN packets to sockets.
  b) Use a full tunnel to the optional bonding server and implement TCP/UDP reassembly there.
- Impact: Until implemented, device apps routed to the VPN will not have their traffic forwarded. The current app provides an in-app download test to demonstrate main/helper bias and network binding.

2) True bonding for single connections
- Current server implements a minimal TCP CONNECT forwarder; no subflow aggregation.
- Path: define a framing protocol and implement per-connection striping across two TLS tunnels, with sequence numbers and reassembly on the server.
- Metrics: Implement congestion feedback and per-link health-based scheduling.

3) CI emulator-based tests
- GitHub-hosted runners can run Android emulators but setup is heavy and slow. Current CI omits emulator-based E2E by default.
- Path: Add a dedicated workflow to boot an AVD and run Espresso tests; or use Firebase Test Lab.

4) Release signing
- Requires keystore and secrets. Workflow is provided but needs secrets added.

5) TLS certificates for server
- Scripts provided for Certbot but require a domain and VPS SSH access.

6) Per-app routing UX
- UI wiring for per-app selection is not implemented yet. The VPN builder supports allow/disallow APIs, but enabling it without TUN forwarding would blackhole traffic. Implement only after (1).
