# Roadmap

This document outlines the near-term tasks for Path A and Path B.

## Path A (On-device steering via VpnService)
- DNS UDP forwarder: per-network DatagramSocket + NAT mapping, reply to TUN
- Expand UDP (QUIC handshake pass-through)
- TCP tun2socks skeleton: simple SYN tracking, per-network socket binding
- Per-app routing UI once forwarding is solid

## Path B (Server bonding)
- Tunnel endpoints (/tunnel) per link (main/helper), client TunnelClient opens both
- Framed subflows with sequence numbers and reassembly on server
- Congestion/health feedback to bias split and handle failover
- Integrate scheduler with TunnelClient to dynamically adjust helper fraction
