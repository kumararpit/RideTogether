# SOS Ack Reliability & Banner Dismissal

Fix for the reported rider-report flow faults:

1. **SOS kept resurfacing/sending after the sender tapped "I am okay"** — `resolveSos()` was
   fire-and-forget: the Firestore `isResolved=true` transaction had **one attempt**, and on
   failure the error was only logged. The sender's own device cleared the banner via a
   session tombstone, but the unresolved document stayed live on the server, so every other
   member kept seeing, acknowledging, and reacting to the alert indefinitely (and a ride
   re-entry on any device re-applied it).
2. **Acknowledgment reached only the acknowledging user, not the SOS sender** — the ack
   transaction also had a single attempt; when the write failed (dead zone, transient
   auth/rules error), the optimistic local copy made the ack look successful on the
   acking device only. The sender never learned anyone was coming. The sender's own bar
   also showed only a raw count, no names.
3. **Members had no way to remove the popup** — the banner could be minimized (only after
   acknowledging) but never removed.

## Changes

### `app/src/main/java/com/example/data/firebase/SosReliability.kt` (new)
- `SosRetryPolicy` — bounded retry policy for SOS writes: `MAX_ATTEMPTS = 3`, backoff
  `0 ms → 500 ms → 1000 ms` (capped 2 s). Emergency writes retry a few times, quickly;
  they never block the caller more than a couple of seconds.
- `SosSuppressionState` — session-scoped, per-device suppression of SOS event ids:
  resolve tombstones ("I'm okay" applied locally) **and** manual member dismissals.
  A stale/unresolved snapshot of a suppressed id can never re-apply the alert on that
  device. A *different* (new) SOS event id always surfaces.

### `app/src/main/java/com/example/data/firebase/RideRepository.kt`
- `resolveSosInternal` — the Firestore resolve transaction now retries up to
  `SosRetryPolicy.MAX_ATTEMPTS` with backoff; `CancellationException` is rethrown, never
  retried. Local authoritative behavior unchanged (banner stays cleared, tombstone kept
  even if all attempts fail — a session-resolved alert cannot be resurrected on the
  sender's device).
- `acknowledgeSos` — same retry treatment so acknowledgments actually reach the server
  and propagate to the sender instead of existing only on the acking device.
- `dismissSosBanner()` + `isSosSuppressedThisSession()` (internal, for tests) — manual
  per-device popup removal; strictly local (never resolves or cancels the sender's
  emergency; only the sender or ride leader can resolve).
- Live SOS snapshot listener now filters both kinds of suppression via
  `SosSuppressionState` (replaces the single `lastResolvedSosId` tombstone).

### `app/src/main/java/com/example/ui/components/SosComponents.kt`
- `SosAlertBanner` — new `onDismiss` parameter and a **Clear** button
  (`testTag: dismiss_sos_button`): members can remove the popup from their own screen.
- `ActiveSosBar` — new `acknowledgedNames: List<String>`; the sender now sees
  **who** acknowledged ("Acknowledged by 2 member(s): Priya Test, Rohan Test"),
  not just a count.

### `app/src/main/java/com/example/ui/screens/LiveRideScreen.kt` + `RideViewModel.kt`
- Member banner wired to `viewModel.dismissSosBanner()` → repository suppression.
- Sender's `ActiveSosBar` receives ack names resolved from the members list.

## Firestore contract (unchanged)

Same document shape and fields (`sos/{id}`: `riderId`, `riderName`, `timestampMs`,
`locationSource`, `acknowledgedBy` array, `isResolved`, `resolvedAtMs`, `updatedAt`).
No security-rule changes required; no new collections. Retry changes only timing of the
existing writes.

## Known limitation (behavioral, unchanged)

If **all** resolve attempts fail (rider fully offline for the retry window), other
members' devices keep the alert until it is written; every member can now **Clear** it
manually, and the next live sync from the eventual Firestore write clears the rest.
Manual dismissal is per-device by design — a real emergency must stay visible to the
pack; the sender and leader retain the only resolve authority.

## Verification (actual execution)

- `./gradlew :app:testDebugUnitTest --rerun` → **BUILD SUCCESSFUL — 227 tests, 0 failures**
  (222 prior + 5 new in `SosAckDeliveryAndDismissalTest`).
- `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
- `./gradlew :app:lintDebug` → BUILD SUCCESSFUL (0 errors).
- Device smoke (`fbe12520`, pkg `com.aistudio.ridetogether.vibe`): `adb install -r` →
  Success; launch `com.example.MainActivity` → process alive, no RuntimeErrors.
- **Not exercised on-device**: the multi-account live flow (member-B ack → sender sees
  name/count, sender resolve → member banner clears everywhere, Clear-button behavior on
  a companion device). Requires two simultaneous accounts and was not run in this session.
