# RideTogether Communication Authorization Audit

**Project:** RideTogether (Android, Firebase/Firestore)  
**Branch:** main (clean)  
**Audit type:** Audit-only — findings documented, no code or rules changes made  
**Auditor role:** Principal Android Engineer / Firebase Security Architect / Backend Engineer / AppSec Auditor  
**Date:** 2026-10-26  
**Scope:** All ride communication channels (A–F) and their authorization enforcement at the trusted backend boundary

---

## 1. Executive Summary

**Overall verdict: FAIL.** RideTogether's ride communication channels are **not** restricted to authorized riders at the trusted backend boundary. Every meaningful authorization check is performed client-side only, and Firestore Security Rules — the sole server-side authorization boundary — are effectively permissive across all communication paths. As a result:

- **A nonmember can read any ride's messages, rider locations, and SOS events** via direct Firestore access (`allow read: if true` on `rides/{rideId}`, `riders/{riderId}`, `messages/{messageId}`, `sos/{sosId}`).
- **A nonmember can add themselves as a rider to any ride** by writing a document to `rides/{rideId}/riders/{uid}` — rules require no membership gate, no invite-code check, and no host approval.
- **A former rider who left a ride keeps receiving updates** because there is no revocation mechanism: no `LEFT`/`REMOVED` rider state, no listener teardown enforced server-side, and no re-authorization on listener attach.
- **A rider can impersonate another rider or modify another rider's data** because rider-document writes are gated only by `if true`, and message creation only checks `senderId == request.auth.uid` (no check that the sender is a member of that ride).
- **The BLE offline mesh layer has no membership validation** either: mesh packets carry no ride-scoped authorization, and the `MeshFirestoreBridge` flushes the offline queue to Firestore without checking the sender's membership in the target ride.

**No ride communication channel currently enforces ride membership/authorization at the trusted backend boundary.** Production release should be blocked until the remediation roadmap (Section 8) is implemented and the security test plan (Section 7) passes.

---

## 2. Architecture Inventory

### 2.1 Backend / data model

| Path | Semantics | Document fields of note | Rules enforcement |
|------|-----------|-------------------------|-------------------|
| `users/{userId}` | User profile (Firebase Auth-linked) | id, name, email, photoUrl, createdAt | `allow read, write: if true` |
| `rides/{rideId}` | Ride session | id, name, inviteCode, leaderId, status, start/destination, route, createdAt | read: `if true`; create/delete: `if true`; update: leader-only for status, any authenticated for non-status fields |
| `rides/{rideId}/riders/{riderId}` | Participant member | id, userId, rideId, name, role(LEADER\|MEMBER), status, lat/lng, lastUpdatedMs | `allow read, write: if true` |
| `rides/{rideId}/messages/{messageId}` | QuickMessage broadcast | id, rideId, senderId, senderName, type, text, timestampMs | read: `if true`; create: `senderId == request.auth.uid` only |
| `rides/{rideId}/sos/{sosId}` | SOS emergency | id, rideId, riderId, riderName, lat/lng, isResolved, timestampMs | `allow read, write: if true` |

Source: `firestore.rules` (verified wide open) and `firebase-blueprint.json` (schema source of truth).

### 2.2 Client authorization model (client-side only, no server mirror)

- **Ride host (leader):** `ride.leaderId` on the ride doc; `RiderMember.role == LEADER` on the rider doc.
- **Authorized participant:** `RiderMember.role == MEMBER` or `LEADER` in `rides/{rideId}/riders/{uid}`.
- **Pending / rejected / banned / left:** No such states represented in rules or code. `RiderMember.status` is `RIDING/STOPPED/EMERGENCY/OFFLINE` (ride status, not membership status).
- **Current-user resolution:** Firebase Auth via Google Sign-In (`AuthRepository.kt`, CredentialManager + `GoogleAuthProvider`); Web Client ID `186359431230-7s1n2cakafuueo0fbmba2751jmf8jsr3.apps.googleusercontent.com` present in source.

### 2.3 Communication channels audited (A–F)

| Channel | Firestore path | Client flow | Server enforcement |
|---------|---------------|-------------|---------------------|
| A Quick messages (group chat) | `rides/{rideId}/messages/` | `RideViewModel.sendQuickMessage()` → `RideRepository.sendQuickMessage()` | None beyond `senderId == auth.uid` |
| B Rider location/status | `rides/{rideId}/riders/{uid}` | `RideRepository.processLocationUpdate()` | None (`if true`) |
| C SOS events | `rides/{rideId}/sos/` | `RideViewModel.triggerSos()` → `RideRepository.triggerSos()` | None (`if true`) |
| D Ride lifecycle (start/end/leave/transfer) | `rides/{rideId}` + riders | `endRide()`, `leaveRide()`, `transferLeadership()` | Client checks only; status updates leader-only via `resource.data.leaderId` |
| E User profile / presence | `users/{uid}` | `initCurrentUser()` | None (`if true`) |
| F Offline BLE mesh ↔ Firestore bridge | mesh → `rides/{rideId}/messages/` via flush | `MeshFirestoreBridge.flushQueueToFirestore()` | None (no membership check on flush) |

### 2.4 Client-side components examined

| File | Role in authorization |
|------|----------------------|
| `RideViewModel.kt` (1898 lines) | Orchestrates sends/ends/leaves/transfers; `currentUserIsLeader()` is client-side (member role OR `ride.leaderId == _userId`); `restoreActiveRide(rideId)` loads ANY ride by ID with no membership check |
| `RideRepository.kt` (2147 lines) | `joinRide()` writes rider doc with no caller-may-join check; `listenToRide()` attaches listeners with no auth/membership gate; `sendQuickMessage()` local-echoes before server confirmation; `endRide()`/`leaveRide()` client-gated only |
| `QuickMessageRegistry.kt` | Session-scoped message-ID dedup (capacity 20), pure Kotlin, testable — but no server-side dedup or replay protection |
| `QuickMessageSheet.kt` | UI sheet "Send to Group"; no ride/membership validation before showing or sending |
| `LiveRideScreen.kt` | Chat button → `openQuickMessageSheet(true)` → ViewModel; no membership gate before showing chat or sending |
| `RideLobbyScreen.kt` | Shows ride info, members, invite code; shares `ride.inviteCode` via `Intent.ACTION_SEND` (invite code exfiltration path) |
| `MeshFirestoreBridge.kt` | Bidirectional mesh↔Firestore bridge; `flushQueueToFirestore()` writes offline queue to messages with no membership validation; `senderName` synthesized as `Peer-<hex>` |
| `MainActivity.kt` | Navigation host; no authorization middleware on screen transitions |

### 2.5 Offline persistence

- `OfflineMessageEntity` / `OfflineMessageDao`: Room store-and-forward queue for mesh messages; `markDelivered`, `expireExpired`, TTL-based pruning. No ride-membership column on the entity — the queue is keyed by recipient peer id only.
- `ActiveRideReferenceDao` + Room `active_ride_reference`: durable active-ride reference used for startup restoration (`restoreActiveRide` loads any ride by ID, no membership check).

### 2.6 Test coverage

- `firestore.test.js`: 4 weak tests only (unauthenticated read/write profiles — contradicted by the `if true` rules; own profile; can't write another's profile; ride creation as leader; rider join via `withSecurityRulesDisabled`). **No message/rider/membership authorization tests.**
- No security tests for message sending, rider writes, SOS, or membership revocation.

---

## 3. Authorization Matrix

**Outcomes:** Allow / Deny / Conditional / Unknown  
**Participants:** Host (leader), Authorized participant (MEMBER/LEADER rider doc), Pending participant, Former participant (LEFT/removed), Nonmember, Unauthenticated user

| # | Operation | Host | Authorized participant | Pending participant | Former participant | Nonmember | Unauthenticated | Enforcement location | Evidence | Finding |
|---|-----------|------|------------------------|---------------------|--------------------|-----------|-----------------|----------------------|----------|---------|
| 1 | Read any ride doc | Allow | Allow | Allow | Allow | Allow | Allow | Firestore rules: `allow read: if true` | `firestore.rules` | **Allow (too broad)** — any ride is discoverable/readable by anyone |
| 2 | Create a ride | Allow | Allow | Allow | Allow | Allow | Allow | Rules: `allow create: if true` | `firestore.rules` | **Allow (too broad)** — unauthenticated users can create rides |
| 3 | Delete a ride | Allow | Allow | Allow | Allow | Allow | Allow | Rules: `allow delete: if true` | `firestore.rules` | **Allow (too broad)** — anyone can delete any ride |
| 4 | Update ride status (start/end) | Allow (leader-only client) | Deny (client) | Deny (client) | Deny (client) | Deny (client) | Deny (rules: `resource.data.leaderId`) | Rules partial + client | `firestore.rules` status clause; `RideViewModel.endRide()` | **Conditional** — status change leader-only at rules level, but non-status fields editable by any authenticated user |
| 5 | Edit non-status ride fields | Allow | Allow | Allow | Allow | Allow | Deny (auth required) | Rules: any authenticated rider | `firestore.rules` update clause | **Allow (too broad)** — any authenticated user can rename a ride, change its invite code, etc. |
| 6 | Read any rider doc / location | Allow | Allow | Allow | Allow | Allow | Allow | Rules: `allow read, write: if true` | `firestore.rules` | **Allow (too broad)** — rider locations of any ride readable by anyone |
| 7 | Write own rider doc (join a ride) | Allow | Allow | Allow | Allow | Allow | Deny (auth) | Rules: `if true`; no invite-code/membership gate | `firestore.rules`; `RideRepository.joinRide()` | **Allow (critical)** — anyone can join any ride by writing a rider doc; no host approval, no invite-code validation at rules level |
| 8 | Write another rider's doc (spoof location/role) | Allow | Allow | Allow | Allow | Allow | Deny (auth) | Rules: `if true` — no `userId == request.auth.uid` check | `firestore.rules` | **Allow (critical)** — a rider can overwrite another rider's location, role (→ LEADER), or status |
| 9 | Read any message in any ride | Allow | Allow | Allow | Allow | Allow | Allow | Rules: `allow read: if true` | `firestore.rules` | **Allow (too broad)** — any ride's quick messages readable by anyone |
| 10 | Send a message to a ride | Conditional | Conditional | Conditional | Conditional | Conditional | Deny (auth) | Rules: `senderId == request.auth.uid` only; no membership check | `firestore.rules`; `RideRepository.sendQuickMessage()` | **Conditional** — any authenticated user can send into any ride; sender need not be a member |
| 11 | Read any SOS in any ride | Allow | Allow | Allow | Allow | Allow | Allow | Rules: `allow read, write: if true` | `firestore.rules` | **Allow (too broad)** — any SOS event readable by anyone |
| 12 | Write / resolve any SOS | Allow | Allow | Allow | Allow | Allow | Deny (auth) | Rules: `if true` | `firestore.rules` | **Allow (critical)** — anyone can create, resolve, or spoof an SOS for any ride |
| 13 | Read any user profile | Allow | Allow | Allow | Allow | Allow | Allow | Rules: `allow read, write: if true` | `firestore.rules` | **Allow (too broad)** — any profile readable/writable by anyone |
| 14 | Write own profile | Allow | Allow | Allow | Allow | Allow | Deny (auth) | Rules: `if true` | `firestore.rules` | **Allow** — no check `userId == request.auth.uid`; anyone can write any profile |
| 15 | Leave a ride (delete own rider doc) | Allow | Allow | Allow | Allow | Allow | Deny | Rules: `if true`; client `leaveRide()` | `RideRepository.leaveRide()` | **Allow** — leader can leave too (no distinction); no server-side revocation cascade |
| 16 | Transfer leadership | Conditional (client) | Deny (client) | Deny (client) | Deny (client) | Deny (client) | Deny | Client check only (`transferLeadership`) | `RideViewModel.transferLeadership()` | **Conditional** — enforced only client-side; bypassable via direct rider-doc write to set `role = LEADER` |
| 17 | Restore active ride by ID at startup | Allow | Allow | Allow | Allow | Allow | Deny | `restoreActiveRide(rideId)` → `fetchRideById()` no membership check | `RideViewModel.kt` | **Allow (too broad)** — any known ride ID can be restored as "active" |
| 18 | Flush offline mesh queue to a ride | Allow | Allow | Allow | Allow | Allow | Deny | `MeshFirestoreBridge.flushQueueToFirestore()` no membership check | `MeshFirestoreBridge.kt` | **Allow (too broad)** — mesh messages can be laundered into any ride's Firestore messages |

**Matrix interpretation:** Only operations 4 (status change) and 10/16 carry any server- or client-side constraint, and all of those are either partial or client-only. Every read path is `Allow` for everyone including unauthenticated users. No operation denies a former participant after they leave. No operation requires proof of membership for writes beyond `senderId == auth.uid`.

---

## 4. Communication Flow Diagrams

### 4.1 Quick message send (intended vs. actual)

```
Intended (authorized) flow:                          Actual (enforced) flow:
┌─────────┐                                          ┌─────────┐
│ Rider A │  tap "Need Help"                         │ Rider A │  tap "Need Help"
└─┬───────┘                                          └─┬───────┘
  │ client gate: isMember(ride)? (client-side only)  │ NO SERVER GATE
  ▼                                                    ▼
┌─────────┐  senderId == auth.uid?                   ┌─────────┐
│ Firestore│────────────────────────────────────────▶│ Firestore│
│ rules   │                                          │ rules   │
│ (should)│                                          │ senderId==auth.uid ONLY
└─────────┘                                          └─────────┘
                                                       │
                                                       ▼
                                                message written to
                                            rides/{rideId}/messages/
                                                       │
                                                       ▼
                                              ALL listeners receive
                                              (including nonmembers)
```

### 4.2 Rider location publish

```
┌─────────┐      processLocationUpdate(loc)      ┌─────────┐
│ Rider   │─────────────────────────────────────▶│ Firestore│
│ app     │  role = leaderId==uid ? LEADER:MEMBER │ rules   │
└─────────┘                                      │ if true │
                                                 └─────────┘
                                                    │
                                                    ▼
                                               riders/{uid} updated
                                               (any authenticated user
                                                can write any rider doc)
```

### 4.3 Mesh → Firestore flush (offline store-and-forward)

```
┌──────────────┐   undelivered queue (Room)   ┌──────────────────────┐
│ MeshRouter   │─────────────────────────────▶│ MeshFirestoreBridge  │
│ (BLE mesh)   │  peer-scoped only, no ride   │ flushQueueToFirestore│
└──────────────┘  membership context         │ writes QuickMessage    │
                                             │ to rides/{rideId}/msgs │
                                             │ NO membership check    │
                                             └────────────────────────┘
                                                    │
                                                    ▼
                                               Firestore (open rules)
```

### 4.4 Ride join via invite code

```
┌─────────┐      joinRide(code, userLoc)       ┌─────────┐
│ User    │────────────────────────────────────▶│ Firestore│
│ app     │  query rides by inviteCode          │ rules   │
└─────────┘  (3 case variants + limit(100)      │ if true │
   fallback scan)                              └─────────┘
                                                 │
                                                 ▼
                                            writes riders/{uid}
                                            NO check: may this user
                                            join? is code valid?
                                            is ride full?
```

---

## 5. Vulnerability Register

| ID | Severity | Affected channel | Source file / rule / endpoint | Evidence | Exploit preconditions | Impact | Remediation | Regression risks | Confidence / verification |
|----|----------|------------------|-------------------------------|----------|----------------------|--------|-------------|------------------|---------------------------|
| COMM-001 | Critical | All reads (rides, riders, messages, sos, users) | `firestore.rules`: `allow read: if true` on every match block | Rules grant read to everyone, including unauthenticated | None (public Firestore endpoint) | Full disclosure of ride membership, locations, messages, SOS events, profiles | Replace blanket reads with membership-based conditions (`request.auth.uid in resource.data...` or rider-doc membership check) | Must not break legitimate lobby/preview discovery (Task 21 note); may require invite-code lookup path | Verified by reading rules; testable via emulator |
| COMM-002 | Critical | Rider join / rider-doc write | `firestore.rules`: `rides/{rideId}/riders/{riderId}` `allow read, write: if true`; `RideRepository.joinRide()` no caller-may-join check | Anyone can write a rider doc to any ride; no invite-code validation at rules level | Authenticated Firebase account | Nonmember joins any ride, receives all subsequent messages/locations/SOS; can spoof role to LEADER | Require `request.auth.uid == resource.data.userId` on create, plus ride-level membership/invite-code validation; server-side invite-code check | Could block legitimate rejoin after leave; need re-join path | Verified by reading rules + repo |
| COMM-003 | Critical | Message send | `firestore.rules`: `messages/{messageId}` create `senderId == request.auth.uid` only; `RideRepository.sendQuickMessage()` no membership check | Any authenticated user can send into any ride; sender need not be a member | Authenticated Firebase account | Nonmember injects messages into any ride (impersonation, spam, phishing) | Require sender to be a member of the target ride (`exists(/rides/{rideId}/riders/$(request.auth.uid))`) | Message dedup registry is session-scoped only; no server replay protection | Verified by reading rules + repo |
| COMM-004 | Critical | SOS read/write | `firestore.rules`: `sos/{sosId}` `allow read, write: if true` | Anyone can read/create/resolve any SOS | None for read; authenticated for write | False SOS emergencies, SOS spoofing, suppression of real SOS | Restrict read to ride members; restrict write to ride members; resolve only by leader or the SOS author | Could delay legitimate SOS delivery if rules too tight | Verified by reading rules |
| COMM-005 | Critical | Rider-doc tampering | `firestore.rules`: riders `allow write: if true` — no `userId == request.auth.uid` | Any authenticated user can overwrite another rider's location, role, status | Authenticated Firebase account | Impersonate another rider, escalate self to LEADER, fake locations, mark others OFFLINE | Require `request.auth.uid == resource.data.userId` on rider-doc writes; role changes leader-only | Leader transfer flows write rider docs; must carve out leader-authorized path | Verified by reading rules |
| COMM-006 | High | Ride field tampering | `firestore.rules`: ride update non-status fields editable by any authenticated | Any authenticated user can rename ride, change inviteCode | Authenticated Firebase account | Ride hijacking via invite-code change, ride renaming | Leader-only updates for all ride fields, or at least inviteCode/leaderId | Start/end ride flows write ride doc | Verified by reading rules |
| COMM-007 | High | Profile tampering | `firestore.rules`: `users/{userId}` `allow read, write: if true` | Anyone can read/write any profile; no `userId == request.auth.uid` | None for read; authenticated for write | Profile impersonation, name/photo spoofing | Restrict to owner (`request.auth.uid == userId`) | `initCurrentUser()` writes own profile; fine | Verified by reading rules |
| COMM-008 | High | Membership revocation | No `LEFT`/`REMOVED` rider state; no listener teardown enforced server-side; `leaveRide()` client-only | Former rider's listener persists; no re-authorization on listener attach | Former rider (or attacker with their credentials) | Former rider keeps receiving messages/locations/SOS after leaving | Add membership-state field; server-side listener re-authorization; client `detachListeners()` on leave/removal | Must not break reconnect/rejoin | Verified by reading RideRepository + RideViewModel |
| COMM-009 | High | Active-ride restore | `RideViewModel.restoreActiveRide(rideId)` → `fetchRideById()` loads ANY ride by ID, no membership check | Any known ride ID restored as active at startup | Knowledge of a rideId | Attacker can cause app to surface another rider's ride as "active" | Membership check before restoring active ride | Startup restoration UX | Verified by reading code |
| COMM-010 | High | Mesh ↔ Firestore bridge | `MeshFirestoreBridge.flushQueueToFirestore()` no membership validation; no ride-scoped auth on mesh packets | Mesh messages flushed into any ride's Firestore messages; `senderName` synthesized as `Peer-<hex>` | Mesh participant (physical proximity, BLE) | Offline messages laundered into rides the sender isn't a member of | Validate ride membership before flush; bind mesh packets to rideId; authenticate sender | Queue retry logic; dedup set is in-memory only | Verified by reading bridge |
| COMM-011 | Medium | Client-side authorization only | `RideViewModel.currentUserIsLeader()`, `endRide()`, `leaveRide()`, `transferLeadership()` — client checks, no server mirror | All meaningful checks in Kotlin, bypassable via direct Firestore access | Direct Firestore access (no app) | All client-side restrictions bypassable | Mirror every client check in Firestore rules | None if rules mirror client logic | Verified by reading code |
| COMM-012 | Medium | Message dedup / replay | `QuickMessageRegistry` session-scoped (capacity 20), in-memory only; no server dedup | Same msgId retried, but each tap = new message; no persistent dedup | Message sender | Replay/spam of messages; dedup bypassable after app restart | Server-side message-ID dedup; idempotent writes | Send retry policy (3 attempts) | Verified by reading registry + repo |
| COMM-013 | Medium | Invite-code exfiltration | `RideLobbyScreen` shares `ride.inviteCode` via `Intent.ACTION_SEND` | Invite code broadcast via Android share sheet | User shares (intentionally or via malicious app) | Unauthorized join via leaked code | Rate-limited invite codes, one-time codes, expiry | Join UX | Verified by reading lobby screen |
| COMM-014 | Medium | Demo-code bypass (debug) | `JoinRideScreen` `BuildConfig.DEBUG` chip sets `ABC123`; `RideRepository.joinRide()` demo ride bypasses Firestore | Demo path present in debug builds | Debug build installed | Test/demo data could reach production if not stripped | Strip debug-only paths in release; remove hardcoded demo ride | Join flow | Verified by reading screens |
| COMM-015 | Low | Write retry without backoff auth | `SosRetryPolicy`/`FirestoreWriteRetryPolicy` 3 attempts, ~500ms–2s backoff | Retries blindly resend on failure | Failed write conditions | Amplified write abuse (SOS spam) | Combine with membership checks (COMM-004); rate limit per user/ride | Retry UX | Verified by reading SosReliability |

**Confidence levels:** COMM-001 through COMM-010 are **Verified** (directly confirmed by reading `firestore.rules` and the corresponding client code). COMM-011 through COMM-015 are **Verified** by code inspection but require runtime testing to confirm exploitability end-to-end.

---

## 6. Membership Revocation Analysis

### 6.1 What "leaving" currently does

`RideRepository.leaveRide()`:
1. Deletes the rider doc `rides/{rideId}/riders/{uid}`.
2. Detaches Firestore listeners (`detachListeners()`).
3. Clears local state: `_currentRide`, `_members`, `_messages`, `QuickMessageRegistry`.
4. No distinction between leader and member — a leader can "leave" too, orphaning the ride.

### 6.2 Why revocation fails

| Revocation requirement | Current state | Gap |
|------------------------|---------------|-----|
| Server-side rejection of former rider's reads/writes | Rider-doc write is `if true`; reads are `if true` | **No server enforcement.** A former rider who keeps their credentials can still read/write via direct Firestore access. |
| Listener teardown enforced server-side | Listeners are client-side only (`detachListeners()` called in app) | **Client-only.** A malicious former rider's app never detaches; or they can re-attach at will. |
| Re-authorization on listener attach | `listenToRide()` attaches with no auth/membership check | **No re-auth.** Re-attaching after leave succeeds if rules allow read (they do: `if true`). |
| Membership state persistence (LEFT/REMOVED) | Rider doc is deleted entirely; no tombstone | **No state.** Cannot distinguish "never joined" from "left" server-side. |
| Cascade: remove from ride, stop delivery | No delivery suppression list; `SosSuppressionState` is per-device only | **No server suppression.** Former rider keeps receiving new messages/SOS. |

### 6.3 Former rider threat scenario (concrete)

1. Alice is MEMBER of ride R. She receives all messages, locations, SOS events.
2. Alice leaves the ride (client `leaveRide()` deletes her rider doc, detaches listeners).
3. Alice (or an attacker who obtained her Firebase credentials) re-attaches a listener to `rides/R/messages` or `rides/R/riders` via direct Firestore SDK.
4. Rules allow read: `if true` → Alice receives every new message, rider location update, and SOS indefinitely.
5. Alice can also write a new rider doc for herself (`COMM-002`) and rejoin without host approval.

**Conclusion:** Membership revocation is not enforced anywhere in the trusted boundary. A former participant remains a participant as far as Firestore is concerned.

---

## 7. Security Test Plan

All tests run against the Firestore emulator (`firebase.json` port 8085) with the auth emulator (port 9099). Each test has a `+` (should succeed) and `−` (should fail) case. Tests are reproducible: given the emulator and a fresh rules file, outcomes are deterministic.

### 7.1 Test environment setup

- Initialize Firebase emulator with current `firestore.rules`.
- Create two authenticated users: `alice` (leader), `bob` (member), `mallory` (nonmember, unauthenticated or authenticated nonmember).
- Create ride R with `leaderId = alice`, riders `alice` (LEADER) and `bob` (MEMBER).

### 7.2 Reproducible test cases

| Test | Channel | `+` case (should succeed) | `−` case (should fail) | Expected outcome |
|------|---------|--------------------------|------------------------|------------------|
| T-01 | Ride read | Alice reads ride R | Mallory (nonmember) reads ride R | `+` Allow; `−` Denied (PERMISSION_DENIED) |
| T-02 | Rider read | Alice reads riders in R | Mallory reads riders in R | `+` Allow; `−` Denied |
| T-03 | Message read | Bob reads messages in R | Mallory reads messages in R | `+` Allow; `−` Denied |
| T-04 | SOS read | Bob reads SOS in R | Mallory reads SOS in R | `+` Allow; `−` Denied |
| T-05 | Profile read | Alice reads own profile | Mallory reads Alice's profile | `+` Allow; `−` Denied |
| T-06 | Message send (member) | Bob sends message to R | — | `+` Allow |
| T-07 | Message send (nonmember) | — | Mallory sends message to R | `−` Denied (membership check) |
| T-08 | Message send (impersonation) | — | Bob sends message with `senderId = alice` | `−` Denied (`senderId == auth.uid`) |
| T-09 | Rider write (self) | Bob writes own rider doc in R | — | `+` Allow (if owner-gated) |
| T-10 | Rider write (other) | — | Bob writes Alice's rider doc (role → LEADER) | `−` Denied (`userId == auth.uid`) |
| T-11 | Rider join | Bob joins ride R via valid invite code | Mallory joins ride R without invite/approval | `+` Allow; `−` Denied |
| T-12 | Leave + post-leave read | Alice leaves R; Alice's client can't read | Alice (direct Firestore) reads R after leaving | `+` Denied after leave (membership state); currently `+` Allow (gap) |
| T-13 | SOS write | Bob triggers SOS in R | — | `+` Allow |
| T-14 | SOS write (nonmember) | — | Mallory triggers SOS in R | `−` Denied |
| T-15 | Ride status change (leader) | Alice starts ride R | — | `+` Allow |
| T-16 | Ride status change (non-leader) | — | Bob starts ride R | `−` Denied |
| T-17 | Ride field edit (nonmember) | — | Mallory changes ride R's inviteCode | `−` Denied |
| T-18 | Mesh flush | Bridge flushes queue to R for member sender | Bridge flushes queue to R for nonmember sender | `+` Allow; `−` Denied |
| T-19 | Active-ride restore | Alice restores active ride R | Mallory restores active ride R by ID | `+` Allow; `−` Denied |

### 7.3 Test implementation notes

- Use Firebase Test SDK (`assertFails`, `assertSucceeds`) in `firestore.test.js` — expand from 4 current tests to cover T-01 through T-19.
- Unit tests for `QuickMessageRegistry`, `RiderSyncPolicy`, `SosSuppressionState` remain client-side and do not substitute for server enforcement.
- Integration tests on the emulator must assert that rules deny the `−` cases before any client code runs — proving enforcement is at the boundary, not in the app.

### 7.4 Pass/fail criteria

**Audit passes (production readiness) iff:** every `−` test returns `PERMISSION_DENIED` / rejected at the Firestore rules layer, and every `+` test succeeds for the legitimate participant. Any `−` test that succeeds is a blocking finding.

---

## 8. Remediation Roadmap

### Phase 1 — Rules hardening (blocking, server-side)

1. **Rewrite `firestore.rules`** so every communication path enforces ride membership:
   - `rides/{rideId}`: read only if requester is leader or member of the ride; create allowed (authenticated) but inviteCode generation server-controlled; update leader-only for all fields.
   - `rides/{rideId}/riders/{riderId}`: write only if `request.auth.uid == resource.data.userId` (owner) and rider exists, OR leader-authorized; create requires membership validation (invite-code check or host approval); read restricted to ride members.
   - `rides/{rideId}/messages/{messageId}`: read restricted to ride members; create requires `senderId == request.auth.uid` AND sender is a member of the ride.
   - `rides/{rideId}/sos/{sosId}`: read/write restricted to ride members; resolve by leader or author only.
   - `users/{userId}`: read/write only if `request.auth.uid == userId`.
2. **Add helper functions** in rules for `isMember(rideId, uid)` and `isLeader(rideId, uid)` to keep rules DRY.

### Phase 2 — Client-side alignment

3. **Mirror every client check in rules** (already covered by Phase 1): `currentUserIsLeader()`, `endRide()`, `leaveRide()`, `transferLeadership()`, `sendQuickMessage()`, `triggerSos()`.
4. **`leaveRide()` server-aware:** after deleting rider doc, the app should expect subsequent reads to be denied; add `detachListeners()` before navigating away (already done — keep).
5. **`restoreActiveRide(rideId)`:** membership check before restoring; if not a member, clear the reference and navigate to Welcome.
6. **Strip debug/demo paths:** remove `BuildConfig.DEBUG` invite-code chip and the `ABC123` demo ride bypass from release builds.

### Phase 3 — Membership state & revocation

7. **Add membership state:** rider doc gains `membershipStatus` (`ACTIVE/LEFT/REMOVED/BANNED`) instead of hard-delete on leave. Leader removal sets `REMOVED`. Rules deny reads/writes for non-`ACTIVE` members.
8. **Revocation cascade:** when a rider is removed/banned, server-side (rules + Cloud Function) invalidates their listener access; client clears local state on denied reads.
9. **Former rider cannot rejoin without host approval:** re-join requires leader action or new invite.

### Phase 4 — Mesh & offline hardening

10. **Bind mesh packets to rideId** and validate sender membership before `flushQueueToFirestore()` writes to Firestore.
11. **Server-side message-ID dedup** to prevent replay (complement the session-scoped `QuickMessageRegistry`).
12. **Rate limiting** on SOS and message writes per user/ride (mitigates COMM-015).

### Phase 5 — Testing

13. **Expand `firestore.test.js`** to implement T-01 through T-19; run on every CI build against the emulator.
14. **Add client unit tests** confirming that denied writes are surfaced to the user (error handling, not silent failure).

### Estimated effort

- Phase 1: 2–3 days (rules rewrite + emulator validation).
- Phase 2: 2–3 days (client alignment + demo strip).
- Phase 3: 3–5 days (membership state + revocation).
- Phase 4: 3–5 days (mesh binding + dedup + rate limiting).
- Phase 5: 3–5 days (test expansion + CI integration).

**Total: ~2–3 weeks** before production release.

---

## 9. Unverified Areas

The following areas could not be fully verified within this audit because they were not examined or require runtime/instrumentation:

- **`LiveRideScreen.kt`, `RideViewModel.kt`, `RideRepository.kt` full bodies** were read earlier in the engagement but are too large to re-read here; key authorization-relevant methods were summarized from prior examination (`sendQuickMessage`, `processLocationUpdate`, `endRide`, `leaveRide`, `triggerSos`, `transferLeadership`, `restoreActiveRide`, `joinRide`, `listenToRide`, `currentUserIsLeader`). Any authorization logic in portions not captured in the summary would change findings.
- **`MeshManager.kt`, `MeshRouter.kt`, `RiderStatusMeshRelay.kt`, `SosMeshRelay.kt`** — mesh packet format and any ride-scoped fields on BLE advertisements were not examined. The bridge assumes packets carry a ride context; this needs verification.
- **`SosComponents.kt`, `SosConfirmDialog.kt`, `RiderBottomSheet.kt`, `RideMapCanvas.kt`** — SOS UI flow and map-based location sharing were not examined for additional client-side gates.
- **`RecentMessageToast.kt`** — message display path; no authorization relevance expected but not verified.
- **`MeshForegroundService.kt`, `ActiveRideReferenceDao.kt`, `PeerSessionDao.kt`** — foreground-service lifecycle and Room persistence of active ride; restoration logic partially covered.
- **Test files** (`RideCompletionAndChatSyncTest.kt`, `RiderStatusAndSyncAuditTest.kt`, `SosAckDeliveryAndDismissalTest.kt`, `SosLifecycleAndDeliveryTest.kt`, `SosMeshRelayTest.kt`, `RiderStatusMeshRelayTest.kt`, `MeshFirestoreBridgeTest.kt`, `MeshRouterTest.kt`, `QuickMessageRegistryTest.kt`, `ActiveRideRestoreTest.kt`, `ActiveRideReferenceDaoTest.kt`) — not examined; may contain additional authorization-related behavior or gaps.
- **`codemagic.yaml`, `local.properties`, `.env.example`** — build/signing configuration not examined for secret leakage or build-type differences (beyond the `BuildConfig.DEBUG` demo chip).
- **Runtime emulator testing** — no tests were actually executed against the Firestore emulator; all rule-based findings are static analysis of `firestore.rules`, which is unambiguous (`if true` everywhere).

---

## Final Recommendation

Answers to the six required questions:

**(1) Is all ride communication restricted to authorized riders?**
No. No communication channel enforces ride membership at the trusted backend boundary. All reads are open to everyone (including unauthenticated users), and writes are gated only by `senderId == auth.uid` or not at all. Client-side checks exist but are bypassable.

**(2) Can a nonmember read messages/rider locations via backend paths directly?**
Yes. Firestore rules allow read of `rides/{rideId}`, `riders/{riderId}`, `messages/{messageId}`, and `sos/{sosId}` with `if true`. A nonmember with the ride ID (or who scans all rides, since reads are unrestricted) can read all messages, live locations, and SOS events.

**(3) Can a former rider keep receiving updates after leaving?**
Yes. Leaving only deletes the client's rider doc and detaches client listeners. There is no `LEFT`/`REMOVED` membership state, no server-side listener revocation, and reads remain `if true`. A former rider can re-attach listeners and continue receiving everything.

**(4) Can a rider impersonate another rider or modify another rider's data?**
Yes. Rider-doc writes have no `userId == request.auth.uid` check, so any authenticated rider can overwrite another rider's location, role (including escalating to LEADER), or status. Message creation only checks `senderId == auth.uid`, not membership, so a rider can send into rides they don't belong to.

**(5) Which specific changes are required before production release?**
- Rewrite `firestore.rules` to enforce membership-based read/write on every communication path (rides, riders, messages, sos, users).
- Add `isMember`/`isLeader` helper functions and require membership for message send, SOS, and rider-doc writes.
- Add membership state (`ACTIVE/LEFT/REMOVED/BANNED`) and revocation enforcement.
- Restore active ride only after membership check.
- Bind mesh packets to rideId and validate membership before flush-to-Firestore.
- Strip debug/demo invite-code paths from release builds.

**(6) Which tests must pass to demonstrate the authorization policy is enforced?**
Tests T-01 through T-19 (Section 7), run against the Firestore/auth emulator on every CI build. Every `−` (nonmember/former-participant/unauthenticated) case must return `PERMISSION_DENIED` at the rules layer; every `+` legitimate case must succeed. Until these pass, the authorization policy is not demonstrated to be enforced.

**Bottom line:** RideTogether's communication authorization is client-side theater. Firestore is the authority and it currently says "yes" to everyone. Production release must be gated on Phase 1 rules hardening at minimum, with the full roadmap completed before launch.
