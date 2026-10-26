# Bitchat Android Architecture Analysis — RideTogether Offline Mesh Reference

**Source:** `permissionlesstech/bitchat-android`, commit `d1da9716`, branch `main`  
**Investigation only** — read-only, no modifications. GPL v3 license conflict noted → RideTogether will use a clean-room independent implementation.

---

## 1. Component Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                         UI / Application Layer                       │
│  (ChatActivity, ChatViewModel, MessageAdapter, Compose screens)    │
└─────────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    BluetoothMeshService (Foreground Service)         │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐ │
│  │ MeshCore    │  │ MeshTransport│ │ PacketRelay │ │ StoreForward│ │
│  │ (orchest.)  │  │ (BLE GATT)  │  │ Manager     │  │ Manager     │ │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘ │
│         │                │                │                │        │
│         ▼                ▼                ▼                ▼        │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │              PacketProcessor (parse/validate/route)          │  │
│  └──────────────────────────────────────────────────────────────┘  │
│         │                    │                    │                │
│         ▼                    ▼                    ▼                ▼
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────┐│
│  │ SecurityMgr │    │ NoiseSession│    │ BinaryProto │    │ PeerFp  ││
│  │ (sign/verify│    │ Manager     │    │ (framing)   │    │ Manager ││
│  │  Ed25519)   │    │ (XX handshake)│    │             │    │         ││
│  └──────┬──────┘    └──────┬──────┘    └──────┬──────┘    └────┬────┘│
│         │                  │                  │             │      │
│         └──────────────────┴──────────────────┴─────────────┘      │
│                           │                                         │
│                           ▼                                         │
│                 ┌───────────────────────┐                           │
│                 │   Android BLE Stack   │                           │
│                 │ (Adapter, GATT server │                           │
│                 │  / client, scanner)   │                           │
│                 └───────────────────────┘                           │
└─────────────────────────────────────────────────────────────────────┘
```

### Components and Responsibilities

| Component | Responsibility | Key Files |
|---|---|---|
| **BluetoothMeshService** | Foreground service hosting BLE adapter, advertiser, scanner, GATT server/client; keeps BLE alive in background | `BluetoothMeshService.kt` |
| **MeshCore** | Orchestrates peer discovery, connection, session management, message dispatch | `MeshCore.kt` |
| **MeshTransport** | BLE GATT transport layer — advertising payload, scan callbacks, GATT read/write, MTU handling | `MeshTransport.kt` |
| **PacketProcessor** | Parses binary packets, validates magic/version/TTL, routes to local or relay, deduplicates | `PacketProcessor.kt` |
| **PacketRelayManager** | Mesh relay: TTL decrement, duplicate-hop loop prevention, adaptive relay probability | `PacketRelayManager.kt` |
| **StoreForwardManager** | Durable offline queue: persist undelivered messages, TTL expiration, retry on reconnect | `StoreForwardManager.kt` |
| **SecurityManager** | Signing/verification orchestration (Ed25519), identity management | `SecurityManager.kt` |
| **NoiseSessionManager** | Noise XX handshake state machine, per-peer session keys, rekeying | `NoiseSessionManager.kt` |
| **NoiseEncryptionService** | Static identity keys, Ed25519 signing keys, channel encryption, encrypt/decrypt API | `NoiseEncryptionService.kt` |
| **BinaryProtocol** | Binary packet framing: header fields, TTL, flags, source routing, fragmentation | `BinaryProtocol.kt` |
| **PeerFingerprintManager** | SHA-256 fingerprint → peer ID mapping, centralized (no local storage of mappings) | `PeerFingerprintManager` |

---

## 2. Message Flow Sequence

### Direct (single-hop) delivery

```
Sender                              Receiver
  │                                   │
  │ 1. Encrypt (Noise session key)    │
  │ 2. Sign (Ed25519)                 │
  │ 3. Frame (BinaryProtocol)         │
  │ 4. BLE GATT write (MTU chunks)    │
  │──────────────────────────────────▶│
  │                                   │ 5. Receive GATT notification
  │                                   │ 6. Verify Ed25519 signature
  │                                   │ 7. Decrypt (Noise session)
  │                                   │ 8. Deliver to UI
  │◀──────────────────────────────────│ 9. ACK (optional)
```

### Multi-hop (relay) delivery

```
Source                    Relay A                    Relay B                   Dest
  │                         │                         │                        │
  │ 1. Packet (TTL=3)       │                         │                        │
  │────────────────────────▶│                         │                        │
  │                         │ 2. Verify sig, TTL→2    │                        │
  │                         │ 3. Dedup (msgID+TTL)    │                        │
  │                         │ 4. Re-sign or forward   │                        │
  │                         │────────────────────────▶│                        │
  │                         │                         │ 5. TTL→1, dedup        │
  │                         │                         │ 6. Forward             │
  │                         │                         │────────────────────────▶│
  │                         │                         │                        │ 7. Deliver
```

Key behaviors:
- **TTL decrement** at each hop; packet dropped at TTL=0.
- **Duplicate detection**: `(senderID, messageID)` pair plus TTL seen set prevents re-relaying the same packet.
- **Adaptive relay probability**: relays forward with a probability that decreases as peer density increases, reducing broadcast storms.
- **Store-and-forward**: if destination is offline, message is queued locally (StoreForwardManager) and retried when the peer reappears.

---

## 3. Trust Boundaries

| Boundary | What crosses | Trust model |
|---|---|---|
| **UI → BluetoothMeshService** | Outgoing messages, send requests | Trusted (same app, same UID) |
| **BLE advertising** | Peer discovery beacons (unencrypted) | Untrusted — anyone can advertise; no identity binding |
| **BLE GATT connection** | Encrypted Noise session | Mutual auth via Noise XX; static keys pinned after first handshake |
| **Relay hops** | Forwarded ciphertext | Relay never sees plaintext; trusted only to forward, not to inspect |
| **Peer ID claim** | `senderID` field in packet header | Untrusted until Noise handshake proves possession of the static key matching the derived peer ID |
| **Store-forward queue** | Persisted ciphertext on disk | Device-local trust; encrypted at rest via Noise session keys |

**Critical trust issue:** A peer can claim any `senderID` in the packet header. The Noise handshake binds the claimed ID to the static public key (`NoisePeerIdentity.matchesClaimedPeerID`). Packets from peers without an established session are dropped.

---

## 4. Transport Selection & Retry

| Aspect | Behavior |
|---|---|
| **Transport** | BLE GATT only (advertising for discovery, GATT server/client for data) |
| **Discovery** | Periodic advertising + scanning; peers exchange device info in advertising payload |
| **Connection** | GATT client connects to discovered peer's GATT server; both roles supported |
| **MTU** | GATT MTU negotiated; packets larger than MTU are fragmented (BinaryProtocol supports fragmentation/reassembly) |
| **Retry** | Handshake timeout (10s), stale handshake sweep (2s interval); no application-level ACK/retry for mesh packets beyond store-forward |
| **Rekey** | Time-based (1 hour) and message-count-based (1k messages) rekey triggers |

---

## 5. Persistence & Lifecycle

| Aspect | Behavior |
|---|---|
| **Identity persistence** | Static Noise key pair + Ed25519 signing key pair persisted via `SecureIdentityStateManager` (SharedPreferences/EncryptedSharedPreferences) |
| **Session persistence** | Noise sessions are in-memory only; re-established on reconnect via handshake |
| **Store-forward** | Undelivered messages persisted to disk with TTL; flushed when peer comes online |
| **Foreground service** | `BluetoothMeshService` runs as foreground service to keep BLE alive when app is backgrounded |
| **Peer fingerprint** | Not persisted locally — rebuilt from authenticated sessions only (peer ID rotation safe) |
| **Message dedup window** | 5-minute duplicate/replay detection window |

---

## 6. Adoptable vs Non-Adoptable Concepts (for RideTogether)

### Adoptable (clean-room reimplementation, not copied)

| Concept | How RideTogether adapts it |
|---|---|
| Binary packet framing with TTL, flags, senderID, messageID | Use for offline mesh packets; frame format independent of Bitchat's exact byte layout |
| Noise XX handshake with X25519 | Per-peer session keys for encrypted offline messaging |
| Ed25519 packet signing | Sign each mesh packet for integrity + sender authentication |
| SHA-256 fingerprint of static key as peer ID | Ride group member identity for mesh routing |
| TTL-decrement mesh relay with duplicate detection | Bounded multi-hop forwarding within a ride group |
| Store-and-forward queue with TTL expiration | Local Room queue for offline messages, synced to Firestore on reconnect |
| Foreground service for BLE background operation | Extend existing `RideLocationService` or add BLE foreground service |
| 5-minute replay window | Replay protection for mesh packets |
| Session-based encryption (per-peer Noise sessions) | Encrypt offline messages between ride members |

### Non-Adoptable (license-copyleft or architecturally incompatible)

| Concept | Reason |
|---|---|
| Bitchat source code (GPL v3) | Copyleft would infect RideTogether; must write independent implementation |
| Nostr/npub integration | RideTogether uses Firebase Auth, not Nostr |
| Channel passwords / channel encryption | Ride group chat is ride-scoped, not channel-scoped |
| Peer ID rotation / favorites persistence | RideTogether uses stable Firebase UIDs as rider IDs |
| Direct copy of binary wire format bytes | Must define own framing to avoid GPL derivative work |

---

## 7. Relevant Files (Bitchat Reference)

| File | Purpose |
|---|---|
| `BinaryProtocol.kt` | Binary packet framing specification |
| `BitchatMessage.kt` | Message data model |
| `RoutedPacket.kt` | Packet + routing metadata wrapper |
| `MeshCore.kt` | Core mesh orchestration |
| `MeshTransport.kt` | BLE GATT transport |
| `BluetoothMeshService.kt` | Foreground service hosting BLE stack |
| `PacketProcessor.kt` | Parse/validate/route packets |
| `PacketRelayManager.kt` | TTL, dedup, relay probability |
| `StoreForwardManager.kt` | Offline queue + retry |
| `SecurityManager.kt` | Signing/verification orchestration |
| `NoiseSessionManager.kt` | Noise XX handshake state machine |
| `NoiseEncryptionService.kt` | Identity keys, encrypt/decrypt API, Ed25519 sign/verify |
| `AppConstants.kt` | Protocol constants (TTL defaults, retry intervals, MTU) |
| `AndroidManifest.xml` | BLE permissions, foreground service declaration |

---

## 8. Weaknesses Identified (for RideTogether to avoid)

1. **GPL v3 license** — cannot reuse any code; clean-room implementation only.
2. **No application-level ACK** for mesh packets — delivery is best-effort beyond store-forward.
3. **In-memory Noise sessions** — sessions lost on process death; must re-handshake.
4. **No persistence of peer fingerprints** — peer ID rotation breaks continuity; RideTogether uses stable UIDs so this is less of an issue.
5. **Advertising payload is unencrypted** — presence leaks; acceptable for a ride group but worth noting.
6. **No forward secrecy beyond session lifetime** — session keys are ephemeral but static identity keys are long-lived.

---

## 9. Integration Points with RideTogether

| RideTogether existing | Offline mesh mapping |
|---|---|
| `/rides/{rideId}/riders/{riderId}` (Firestore) | Ride group = mesh peer set; riderId = peer ID |
| `RiderMember.id` (Firebase UID) | Derived from SHA-256 of static Noise public key, bound to Firebase UID at ride join |
| `QuickMessage` + `QuickMessageRegistry` | Offline quick messages queued locally, synced to Firestore `/rides/{rideId}/messages` on reconnect |
| `SosEvent` + `/rides/{rideId}/sos` | SOS alerts broadcast via mesh with TTL=3, stored locally, synced to Firestore |
| `RideRepository` (sendQuickMessage, triggerSos) | Extended with mesh send path when Firestore is unavailable |
| `RideDatabase` (Room, version 9) | New entities: `OfflineMessageEntity`, `PeerSessionEntity`, `MeshDeliveryStateEntity` |
| `RideViewModel` | New UI state for offline mesh: peer connectivity, message delivery status, SOS mesh delivery |
| `RideLocationService` (foreground, location type) | Extend or add BLE foreground service type for mesh operation |

---

## 10. License Review Conclusion

Bitchat's `LICENSE.md` is **GPL v3** (the README claims "public domain" but the actual license file is GPL v3 — these conflict; GPL v3 is the legally operative document). RideTogether must **not** copy any Bitchat source code, wire format byte layouts, or algorithm implementations. All concepts listed as "adoptable" above will be reimplemented independently in RideTogether's package (`com.example.data.offline`) with original code.
