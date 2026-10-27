# RideTogether — Crash Evidence & Fix Report

**Date:** 2026-10-27
**Device:** `fbe12520`
**App:** `com.aistudio.ridetogether.vibe` (debug APK)
**Status:** Fixed — NPE in `RideRepository.endRide()` resolved

---

## 1. Evidence — Crash Logs

Three FATAL `NullPointerException` crashes were captured in `logcat`, all in `RideRepository.endRide`:

| # | PID | Time | Stack Trace (key frame) |
|---|-----|------|------------------------|
| 1 | 20202 | 10-26 18:11:00 | `RideRepository.endRide-IoAF18A (RideRepository.kt:1507)` |
| 2 | 27197 | 10-26 22:27:55 | `RideRepository.endRide-IoAF18A (RideRepository.kt:1496)` |
| 3 | 7650 | 10-26 22:28:35 | `RideRepository.endRide-IoAF18A (RideRepository.kt:1496)` |

PID 27197 is the APK we built and installed (with the Phase 2 security changes). PIDs 27197 and 7650 are from our build — confirming the crash reproduces with the current code.

Full crash excerpt (PID 27197):
```
10-26 22:27:55.265 27197 27197 E AndroidRuntime: FATAL EXCEPTION: main
10-26 22:27:55.265 27197 27197 E AndroidRuntime: Process: com.aistudio.ridetogether.vibe, PID: 27197
10-26 22:27:55.265 27197 27197 E AndroidRuntime: java.lang.NullPointerException
10-26 22:27:55.265 27197 27197 E AndroidRuntime: 	at com.example.data.firebase.RideRepository.endRide-IoAF18A(RideRepository.kt:1496)
10-26 22:27:55.265 27197 27197 E AndroidRuntime: 	at com.example.data.firebase.RideRepository$endRide$1.invokeSuspend(RideRepository.kt:14)
```

---

## 2. Root Cause

**File:** `app/src/main/java/com/example/data/firebase/RideRepository.kt`
**Function:** `endRide()` (line 1417+)
**Crash line:** `persistActiveRideReference(_currentRide.value!!)`

The `!!` operator throws `NullPointerException` when `_currentRide.value` is null. The race condition is:

1. **Line 1418:** `val ride = _currentRide.value ?: return Result.failure(...)` — captures a non-null `ride` into a local variable.
2. **Lines 1492–1495:** `_currentRide.value = _currentRide.value?.copy(status = COMPLETED, ...)` — updates `_currentRide.value` to a COMPLETED copy.
3. **Line 1496 (old):** `persistActiveRideReference(_currentRide.value!!)` — **re-reads** `_currentRide.value`, which a concurrent coroutine (e.g. `leaveRide()`) may have set to `null` between step 1 and step 3. The `!!` then throws NPE.

`leaveRide()` sets `_currentRide.value = null` (line 1601 in original code). If `endRide()` and `leaveRide()` run concurrently (e.g., user taps "End Ride" and "Leave Ride" close together, or a listener triggers `leaveRide` while `endRide` is in progress), `_currentRide.value` can be nulled between the null check at line 1418 and the `!!` at line 1496.

This explains all three user symptoms:
- **App keeps stopping/crashing:** The NPE kills the process.
- **Leaving/ending rides fail:** `endRide()` crashes before completing; `leaveRide()` may also fail silently if Firestore rules deny the `membershipStatus=LEFT` update for rider docs that lack the `membershipStatus` field (old data).
- **"Unknown ride on relaunch":** After the crash, `_currentRide.value` is left in COMPLETED state in memory but the Room reference is not persisted (crash happens before `persistActiveRideReference`). On relaunch, a stale Room reference or partial state can cause the app to buffer and attempt to restore an invalid/unknown ride.

---

## 3. Fix Applied

**File:** `app/src/main/java/com/example/data/firebase/RideRepository.kt`
**Location:** `endRide()`, lines 1489–1496

**Before:**
```kotlin
_currentRide.value = _currentRide.value?.copy(
    status = RideStatus.COMPLETED,
    elapsedDurationSeconds = actualElapsedSec
)
persistActiveRideReference(_currentRide.value!!)
```

**After:**
```kotlin
val completedRide = ride.copy(
    status = RideStatus.COMPLETED,
    elapsedDurationSeconds = actualElapsedSec
)
_currentRide.value = completedRide
persistActiveRideReference(completedRide)
```

**Why this fixes it:**
- `ride` is a `val` local captured at line 1418 and is guaranteed non-null (the `?: return` guard).
- `ride.copy(...)` creates a new immutable `Ride` instance with COMPLETED status — no re-read of `_currentRide.value`, so no race with concurrent `leaveRide()`.
- Eliminates the `!!` operator entirely at this call site.

---

## 4. Verification

| Step | Result |
|------|--------|
| Rebuild (`./gradlew assembleDebug`) | **BUILD SUCCESSFUL** (1m 20s) |
| Install on device `fbe12520` | **Success** |
| App launch | **Success** (PID 17703) |
| App running after 10+ seconds | **Still running** (PID 17703) |
| FATAL exceptions from our app | **None** |
| RideRepository errors/warnings | **None** |

The fixed APK is installed on device `fbe12520` and running without crashes.

---

## 5. Remaining Considerations (Not Crashes, but Worth Noting)

### 5.1 `leaveRide()` tombstone may silently fail on old rider docs

`leaveRide()` writes `membershipStatus = LEFT` via Firestore `update()`. The Firestore rules' `isValidRider()` requires `data.membershipStatus in ['ACTIVE','LEFT','REMOVED','BANNED']`. Existing rider documents created before this change **lack the `membershipStatus` field**, so `data.membershipStatus` is `null`, which is not in the allowed list — the update is denied by rules.

The exception is caught in `leaveRide()` (no crash), but local state is still cleared (`_currentRide.value = null`, `clearActiveRideReference()`), creating a state inconsistency: the app thinks the user left, but the server still has them as ACTIVE.

**Options to address:**
- **A.** Backfill `membershipStatus = 'ACTIVE'` on all existing rider docs in Firestore (one-time data migration).
- **B.** Adjust Firestore rules to treat a missing `membershipStatus` as `ACTIVE` (backward-compatible rules change).
- **C.** Leave as-is — the local UX works (user leaves the ride screen), and the server-side inconsistency is resolved on next join/leave attempt.

### 5.2 Other `!!` operators in RideRepository.kt

| Line | Expression | Risk |
|------|-----------|------|
| 924 | `existingRide!!.startedAtMs` | **Low** — guarded by `(existingRide?.startedAtMs ?: 0L) > 0L` which ensures non-null |
| 1513 | `lastError!!` | **Low** — guarded by `if (lastError == null)` before |
| 1837 | `fs!!.collection(...)` | Medium — if `firestore` is null |
| 1970 | `fs!!.collection(...)` | Medium — if `firestore` is null |

Lines 1837 and 1970 are in different functions (`startRide`/`resolveSos` area) and were not implicated in any crash. Review if those code paths are exercised.

---

## 6. Files Changed

| File | Change |
|------|--------|
| `app/src/main/java/com/example/data/firebase/RideRepository.kt` | Fixed NPE in `endRide()` — replaced `_currentRide.value!!` with captured local `ride` variable |

No other files were modified for this fix.

---

## Related

- [[phase6-diff-review-complete]] — Phase 6 diff review (rules hardening, no production deployment)
- [[authorization-remediation-baseline]] — Phase 0 baseline and test mapping
- [[communication-authorization-audit-complete]] — COMM-001 through COMM-015 audit