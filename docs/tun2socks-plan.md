# Tun2socks Implementation Plan

## Overview
Path A implements a tun2socks approach where the Android VPN service intercepts all IP traffic and forwards it through network-bound sockets. This provides:
- Per-app routing via VpnService filtering
- Multi-network load balancing and failover
- Local traffic forwarding without requiring a server
- True multi-path capabilities using Android's ConnectivityManager

## Current Implementation Status

### ✅ Completed
- **UDP Forwarding (Complete)**
  - NAT session tracking with ConcurrentHashMap
  - Multi-network scheduling (DNS→WiFi, QUIC→load balance, etc.)
  - Automatic session cleanup (5-minute timeout)
  - Proper IP/UDP packet construction for TUN replies
  - Support for all UDP traffic (DNS, QUIC, VoIP, gaming, etc.)

- **TCP Forwarding (Structure Complete)**
  - TCP packet parsing with flag detection (SYN, ACK, FIN, RST)
  - Connection state machine (CLOSED, SYN_SENT, ESTABLISHED, etc.)
  - Network selection per connection
  - Socket connection handling
  - Bidirectional forwarding scaffolding

### 🚧 TCP Implementation Gaps (TODOs)
1. **TCP Packet Construction**
   - `sendTcpResponse()`: Build TCP+IPv4 headers for SYN-ACK, FIN-ACK, RST responses
   - `sendTcpDataToClient()`: Build TCP data packets with proper sequence numbers
   - Implement TCP checksum calculation
   - Handle TCP options and window scaling

2. **Sequence Number Management**
   - Track client and server sequence numbers accurately
   - Handle sequence number wraparound
   - Proper ACK number calculation
   - Window size management

3. **TCP State Management**
   - Complete state machine transitions (CLOSE_WAIT, etc.)
   - Handle simultaneous close scenarios
   - Proper connection teardown
   - TCP Keep-alive support

## Network Selection Strategies

### UDP Strategy (Implemented)
```kotlin
// DNS (port 53) -> prefer WiFi for low latency
// QUIC/HTTPS (443) -> round-robin for load balancing  
// Other UDP -> prefer cellular for mobility
```

### TCP Strategy (Implemented)
```kotlin
// HTTPS (443, 8443) -> prefer WiFi for bandwidth
// SSH (22, 2222) -> prefer WiFi for stability
// HTTP/general -> round-robin load balancing
```

## Next Implementation Steps

### Phase 1: Complete TCP Forwarding (1-2 days)
1. **TCP Packet Building**
   ```kotlin
   private fun buildTcpPacket(
       srcAddr: String, dstAddr: String, 
       srcPort: Int, dstPort: Int,
       seq: Long, ack: Long, flags: Int,
       payload: ByteArray = ByteArray(0)
   ): ByteArray
   ```

2. **Sequence Number Tracking**
   ```kotlin
   private fun updateSequenceNumbers(connection: TcpConnection, tcp: TcpHeader, payloadLen: Int)
   private fun calculateAck(connection: TcpConnection): Long
   ```

3. **Integration Testing**
   - Test HTTP connections (port 80)
   - Test HTTPS connections (port 443)
   - Verify multi-network load balancing
   - Test connection teardown

### Phase 2: Per-App Routing (2-3 days)
1. **VPN App Filtering**
   ```kotlin
   // In VpnTunnelService.startVpnLoop()
   val builder = Builder()
       .addAddress("10.0.0.2", 24)
       .addRoute("0.0.0.0", 0)
       .addAllowedApplication("com.android.chrome")  // Only Chrome
       .addDisallowedApplication("com.whatsapp")     // Exclude WhatsApp
   ```

2. **App Selection UI**
   - List installed apps with networking permissions
   - Toggle switches for include/exclude
   - Save preferences in SettingsManager
   - Apply filters when starting VPN

3. **Testing Per-App**
   - Verify only selected apps use VPN tunnel
   - Test split-tunneling scenarios
   - Validate DNS routing per app

### Phase 3: Enhanced Multi-Path Logic (2-3 days)
1. **Network Quality Monitoring**
   ```kotlin
   private fun measureNetworkQuality(network: Network): NetworkQuality
   data class NetworkQuality(val rtt: Long, val bandwidth: Long, val stability: Float)
   ```

2. **Intelligent Scheduling**
   ```kotlin
   private fun selectOptimalNetwork(
       networks: List<NetworkInfo>,
       trafficType: TrafficType,
       qualityHistory: Map<Network, NetworkQuality>
   ): Network
   ```

3. **Failover Logic**
   - Detect network failures
   - Automatically switch connections
   - Maintain session continuity where possible

## Alternative: Library Integration

Instead of implementing TCP packet construction from scratch, consider integrating existing tun2socks libraries:

### Option 1: tun2socks-android
```gradle
// In app/build.gradle
dependencies {
    implementation 'com.github.shadowsocks:tun2socks-android:0.7.6'
}
```

### Option 2: Custom JNI Wrapper
```kotlin
// Native tun2socks via JNI
external fun startTun2Socks(
    tunFd: Int, 
    socksAddr: String, 
    socksPort: Int
): Int
```

## Testing Strategy

### Unit Tests
- PacketParser TCP parsing
- Network selection logic
- Sequence number calculations

### Integration Tests  
- HTTP/HTTPS browsing
- Real-time apps (video calls)
- Large file downloads
- Network switching scenarios

### Performance Tests
- Throughput comparison vs native
- Latency measurements
- Memory usage monitoring
- Battery impact assessment

## Deployment Considerations

### Permissions
```xml
<!-- In AndroidManifest.xml -->
<uses-permission android:name="android.permission.BIND_VPN_SERVICE" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
```

### Performance Optimization
- Connection pooling for HTTP
- Buffer size tuning
- Coroutine dispatcher optimization
- Memory pool for packet buffers

### Error Handling
- Network unreachable scenarios
- Socket timeout handling
- TUN interface errors
- Memory pressure situations

## Timeline Estimate

- **TCP Completion**: 3-5 days
- **Per-App Routing**: 2-3 days  
- **Enhanced Multi-Path**: 2-3 days
- **Testing & Polish**: 2-3 days
- **Total**: ~10-14 days for complete Path A

This provides a functional multi-path VPN without requiring server infrastructure.