# Group Chat Delivery & Shared Ride-Completion Synchronization (Task 21)

Fixes for two reported distributed-systems defects:

## Root causes (established from the code, not assumed)

### Chat (quick messages) — "intermittently missed"
1. **Single-slot overwrite**: the Firestore `messages` listener pushed only the single
   **newest snapshot document** into one `recentMessage` slot. A rapid burst (two+ messages
   inside one listener callback or within the 5-second toast window) silently replaced an
   earlier message before any device displayed it. Members joined mid-session or
   reconnecting saw only the latest — every earlier one was unrecoverable.
2. **Send failures silently swallowed**: `sendQuickMessage()` wrote to Firestore with a
   single attempt inside `repositoryScope.launch`; errors were logged only. On a dead zone,
   the sending device showed its local echo while nobody received anything, and nothing
   surfaced the failure.
3. **No dedup reconciliation**: the sender's local echo and the server snapshot were not
   keyed through one path; listeners also had no error-surfacing path (a failed listener
   just logged and went quiet).

### Leader completion — "not propagated to members"
1. **Members observed COMPLETED but never left navigation**: the shared ride document
   DID carry `status: COMPLETED` and member devices DID receive it — but the ViewModel's
   ride-observer only auto-saved history. It never stopped `RideLocationService`, never
   reset the nav engine, never transitioned the screen. Members stayed in LIVE_RIDE with
   GPS broadcasting.
2. **Stale snapshots could resurrect a completed ride**: the ride listener overwrote
   `status` from every snapshot unconditionally, and the route-setter re-entered
   navigation (`navEngine.setRoute`) for any ride with route points — a delayed `ACTIVE`
   snapshot after completion would reactivate navigation.
3. **No authorization check**: any rider (not only the leader) could write the shared
   ride status; no duplicate-submission guard existed; the Firestore write had no retry
   and failed silently (leader locally done, members never told).
4. **Rejoin could resume a completed ride**: `rejoinActiveRide()` restarted the
   foreground service before checking the shared status.

## Shared canonical state

`RideStatus` (`data/model/RideModels.kt`) is unchanged:
`CREATED, LOBBY, ACTIVE, COMPLETED, CANCELLED` — shared field `rides/{rideId}.status`
remains the authoritative lifecycle. New pure state machine
**`data/model/RideLifecyclePolicy.kt`**:

- `CREATED → LOBBY/ACTIVE/COMPLETED/CANCELLED`, `LOBBY → ACTIVE/COMPLETED/CANCELLED`,
  `ACTIVE → COMPLETED/CANCELLED`
- `X → X` idempotent (repeated COMPLETED snapshots are legal no-ops)
- **REJECTED**: terminal (`COMPLETED`/`CANCELLED`) → anything else — a stale snapshot
  cannot reactivate a finished ride; `ACTIVE → LOBBY/CREATED`; anything → `CREATED`.
  Reopening a completed ride is a product action, never a stale-data side effect.

## Files changed

| File | Change |
|---|---|
| `data/model/RideLifecyclePolicy.kt` | **New** pure lifecycle transition policy |
| `data/firebase/QuickMessageRegistry.kt` | **New** pure message registry: dedup by stable id, chronological ordering (ties broken by id), bounded history (capacity 20) |
| `data/firebase/SosReliability.kt` | `SosRetryPolicy` generalized → `FirestoreWriteRetryPolicy` (typealias keeps SOS call sites compiling) |
| `data/firebase/RideRepository.kt` | Message listener → registry-based (no silent drops, errors surfaced via `messageSendError`); `sendQuickMessage` retries on the same stable id and surfaces final failure; `mergeSharedRideStatus()` policy gate on every ride snapshot; `endRide()` → leader-authorized, idempotent (`isCompletingRide`), transaction + retry + offline-persistence fallback, returns `Result`; `startRide()` lifecycle-guarded; `onSharedRideCompleted()` stops ride-scoped tracking once per client; obsolete-ride guards on all four listeners; test seams (`applySharedRideStatusForTest`, `ingestSharedMessageForTest`, `updateLeaderIdForTest`) |
| `ui/viewmodel/RideViewModel.kt` | `enterSharedCompletedState(rideId)` — exactly once per ride: Room history save, `navEngine.reset()`, `RideLocationService.stopService`, screen → `RIDE_SUMMARY`; route-setter refuses completed/cancelled rides; `endRide()` awaits the repository result and logs failure honestly; `rejoinActiveRide()` routes COMPLETED rides to the summary screen; `recentMessages` + `messageSendError` exposed |
| `ui/components/RecentMessageToast.kt` | Sequential presenter: each new message id displayed exactly once, 5 s each, chronological (was: single `message` param) |
| `ui/screens/LiveRideScreen.kt` | Passes `recentMessages` list to the toast |
| `firestore.rules` | **Exact changes:** `/rides/{rideId}` update: `read/create/delete` unchanged; `update` now requires `(!('status' in request.resource.data) || request.resource.data.status == resource.data.status || (status in ['ACTIVE','COMPLETED','CANCELLED'] && request.auth.uid == resource.data.leaderId))` — i.e. non-status fields update as before, but a status CHANGE is leader-only. `/messages/{messageId}`: `create` requires `request.resource.data.senderId == request.auth.uid`; `update/delete` are denied (message immutability). Rules are **NOT deployed** (no deploy authorization in this session) and must be deployed with `firebase deploy --only firestore:rules` |
| `scripts/firestore-rules-test.js` | **New** Firestore Rules unit tests run against the local emulator |

Observability (Phase F): structured logs at every failure boundary — message
`echo/recv` msgId+senderId + history size, send attempt/outcome, listener errors,
completion attempts/results, stale-transition rejections. No message bodies, GPS
histories, or tokens are logged.

## Verification (actually executed)

- **Gradle**: `:app:testDebugUnitTest` → **BUILD SUCCESSFUL, 248 tests, 0 failures**
  (227 prior + 21 new: `RideLifecyclePolicyTest` 6, `QuickMessageRegistryTest` 6,
  `RideCompletionAndChatSyncTest` 9 — repo/VM-level, fakes only, no live Firebase).
  `:app:assembleDebug` → BUILD SUCCESSFUL. `:app:lintDebug` → BUILD SUCCESSFUL.
- **Firebase Emulator Suite: YES — actually run.** `firebase.json` already defined the
  Firestore emulator (127.0.0.1:8085); `firebase-tools@15.34.0` + `@firebase/rules-unit-testing`
  were installed as dev dependencies; **`scripts/firestore-rules-test.js`: 12/12 PASS** against
  the live emulator, covering: leader completion allowed, member status change denied,
  member hand-write back-to-ACTIVE denied, non-status telemetry updates allowed,
  leadership-transfer semantics (new leader gains completion, old leader loses it),
  sender-identity enforcement, message immutability, chat read access.
  Run locally via: `npx firebase-tools@latest emulators:start --only firestore` then
  `node scripts/firestore-rules-test.js`.
- **Device smoke** (`fbe12520`): `adb install -r` → Success; launch → process alive
  (pid 25250), no runtime errors.
- **Multi-device physical tests: NOT performed.** Only one physical device is available
  in this session; the two/three-device acceptance matrix from Task 10 (cross-device
  message receipt, member leave-navigation UX, reconnect reconciliation on real accounts)
  remains manual/undone.

## Remaining risks

1. **Rules not deployed** — the local enforcement only materializes after
   `firebase deploy --only firestore:rules`. Until then, the server remains open and the
   client-side authorization (leader check in `endRide`) is the only guard.
   ⚠️ The stricter rules MIGHT reject a legacy client that writes `status` from a
   non-leader device (none exists in the current code — verified by grep).
2. **Firestore writes in Robolectric unit tests do not hit a backend** — send-failure
   surfacing is exercised via the `firestore = null` injection; the network-retry path is
   covered by the same code branch but not against a real gRPC failure.
3. **Offline-completion propagation**: if the leader completes fully offline, the plain
   `update()` fallback queues in Firestore persistence and flushes on reconnection;
   if the process dies before the flush, completion can still be lost for members
   (an accepted trade-off vs. blocking the leader entirely).
4. Per-member **read receipts** are NOT implemented (the product UX is a ephemeral
   toast, not a chat list with delivery state); "message sent confirmed" means the
   backend accepted the write, not that every member rendered it.
5. Message-history retention for the toast is session-local (registry clears on
   `leaveRide` / process death); Firestore remains the durable record.
