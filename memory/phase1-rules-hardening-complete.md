---
name: phase1-rules-hardening-complete
description: Phase 1 Firestore rules hardening complete and validated via emulator T-01..T-19
metadata:
  type: project
  originSessionId: 2e3f49a2-87b7-487f-a523-5a00ebb64b9d
  modified: 2026-10-26T13:45:00.000Z
---

Phase 1 (Firestore rules hardening) — DONE, validated.

`firestore.rules` rewritten with default-deny (`match /{document=**} { allow read, write: if false; }`) and membership-based authorization:
- `isMember(rideId, uid)` / `isLeader(rideId, uid)` / `isMemberOrLeader` helpers (trusted server-side boundary).
- `users/{userId}`: owner-only read/create/update; delete false.
- `rides/{rideId}`: read `isMemberOrLeader`; create authenticated + leaderId==callerUid + isValidRide; delete `isLeader`; update `isLeader` + immutableRideKeys (id/createdAt/inviteCode) unchanged.
- `riders/{riderId}`: read `isMemberOrLeader`; CREATE leader-only (isLeader gate) — a user CANNOT self-register membership (task constraint). Update: owner maintains own telemetry (no self-promote to LEADER); leader may change role. Delete: owner leaves or leader removes.
- `messages/{messageId}`: read `isMemberOrLeader`; create requires member + senderId==auth.uid + rideId matches + isValidQuickMessage; update/delete false.
- `sos/{sosId}`: read `isMemberOrLeader`; create requires member + riderId==auth.uid + rideId matches; update by leader or author (lifecycle fields only); delete false.
- Validation helpers: isValidUser/Ride/Rider/QuickMessage/Sos (defense in depth).
- `immutableRideKeys = [id, createdAt, inviteCode]`; `immutableRiderKeys = [id, userId, rideId]`.

Known design gaps documented (Phase 2+ release blockers, NOT weakened in rules):
- Invite-code join-by-code lookup requires a trusted backend function (ride-doc read is membership-gated; no pre-membership discovery query).
- Rider self-registration create is forbidden; join flow needs leader-creates-rider-doc or a trusted backend invite-code validation function (Phase 3 client rework).
- No `membershipStatus` (ACTIVE/LEFT/REMOVED/BANNED) on rider doc yet (Phase 2 schema gap); leave currently hard-deletes the rider doc.

Emulator: cloud-firestore-emulator-v1.22.0.jar running standalone on 127.0.0.1:8085 with `--rules=firestore.rules --project_id=demo-no-project`. Java 21. @firebase/rules-unit-testing ^6.0.0 installed.

Validation: `firestore.test.js` expanded from 4 weak tests to T-01..T-19 (+/- cases) = 30 tests, ALL PASSING against the emulator with the hardened rules. `npm test` runs `node firestore.test.js`. `@firebase/rules-unit-testing` connects to host:port (emulator started separately; the package does not start the emulator).

Related: [[authorization-remediation-baseline]] (update to mark Phase 1 complete), [[security-test-plan]] (T-01..T-19 implemented in firestore.test.js).

Next: Phase 2 membership state model (rider doc `membershipStatus`), Phase 3 secure app paths (restoreActiveRide membership check, messaging/SOS authz, BLE bridge membership validation, strip BuildConfig.DEBUG demo chip), Phase 5 Android build + regression tests, Phase 6 final diff review. No production deployment.
