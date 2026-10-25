# Google Maps Gesture Analysis — Findings Report

**Date:** 2026-10-25
**Target:** `/home/arpit/Downloads/googlemap.apk` (extracted to `/tmp/gmap_analysis/extracted/`)
**Analysis method:** Static string extraction on native `.so` libraries + `.dex` files (deep binary analysis via rea was not possible — see Limitations below)

---

## 1. LIVE_RIDE Back Action Fix (Implemented)

### Problem
On the `LIVE_RIDE` screen, the Android back action only opened the rider list sheet (`viewModel.openRiderListSheet(true)`) with no option to leave or end the ride. Riders had to open the navigation menu or wait for arrival to find the end/leave ride action.

### Root Cause
`MainActivity.kt:256-261` — the `BackHandler` for `AppScreen.LIVE_RIDE` unconditionally opened the rider list sheet:

```kotlin
AppScreen.LIVE_RIDE -> {
    BackHandler {
        viewModel.openRiderListSheet(true)
    }
    LiveRideScreen(viewModel = viewModel)
}
```

### Fix
Moved back handling into `LiveRideScreen.kt` where the end/leave ride confirmation state already lives. The back press now shows `EndRideConfirmDialog`, which offers "End ride" (leader) or "Leave ride" (member) — matching the navigation menu's existing behavior.

**Changes:**
- `MainActivity.kt`: Removed `BackHandler` from `LIVE_RIDE` branch
- `LiveRideScreen.kt`: Added `import androidx.activity.compose.BackHandler` and a `BackHandler { showEndRideConfirm = true }` inside the root `Box`
- `EndRideConfirmDialog` (already existed at `LiveRideScreen.kt:1037-1051`) handles both leader (`viewModel.endRide()` → `RIDE_SUMMARY`) and member (`viewModel.leaveRide()` → `WELCOME`) paths

**Verification:** `:app:compileDebugKotlin` — BUILD SUCCESSFUL.

---

## 2. Google Maps Gesture Analysis (Limited by rea Provider Availability)

### What was analyzed
The APK was extracted to `/tmp/gmap_analysis/extracted/` containing:
- 10 `.dex` files (classes.dex through classes10.dex)
- Native libraries for `arm64-v8a`, including `libgmm-jni.so` (18KB stub), `libgoogle3.so` (36MB), `libelements.so` (3.5MB), and others

### rea analysis — NOT AVAILABLE
The rea reverse-engineering providers (Ghidra, Hopper, IDA, Kuna) are not configured in this environment:
- Ghidra: `GHIDRA_INSTALL_DIR` not set, `analyzeHeadless` unavailable
- Hopper: launcher missing at `/opt/hopper/bin/Hopper`
- IDA: `REA_IDA_MCP_CONFIG` not set
- Kuna: `REA_KUNA_COMMAND` not set

All `mcp__rea__*` calls (open_binary succeeded for loading, but search_strings, list_procedures, analyze_function, etc.) returned `provider_unavailable`. Deep binary analysis (function decompilation, call graphs, xrefs) is therefore not possible.

### Gesture-related evidence extracted via `strings`

From `libgoogle3.so`:
| String | Interpretation |
|--------|---------------|
| `RotateTouch` | Two-finger rotation gesture handler |
| `DragGesture finished but pointer_id` | Drag/pan gesture end detection with pointer tracking |
| `PolylineViewportIntegrityBridge_nativeUpdateViewportAndFollowState` | Follow-mode viewport integrity tracking |
| `nativeUpdateCameraFollowMode` | Camera follow-mode updates (native) |
| `Nav Follow Camera Mode does not have a valid RotationMode` | Follow mode constrains rotation |
| `Invalid CameraMode: Expecting Override Follow camera mode` | Camera mode validation (Follow vs Override) |
| `TextScaleChange` | Text scaling during zoom/transform |
| `contextTransforms` | Transform context for gesture operations |
| `Java_com_google_androidxr_splitengine_SplitEngineInputEvent_nSplitEngineInputEventSetGestureClassification` | AR/XR gesture classification (XR split engine) |
| `Java_com_google_androidxr_splitengine_SplitEngineInputEvent_nSplitEngineInputEventSetGesturePinchScaleFactor` | Pinch scale factor in AR/XR input events |

From `libgmm-jni.so`:
- Stripped 18KB stub — no usable strings (all symbols stripped)

From `libelements.so`:
| String | Interpretation |
|--------|---------------|
| `jniLogGestureEvent` | JNI gesture event logging |
| `Scale factor should not be less than zero` | Scale factor validation |
| `jni_YGConfigSetPointScaleFactorJNI` | Yoga layout scale factor (UI layout, not map) |

From `.dex` files:
| String | Interpretation |
|--------|---------------|
| `panGestureOffset=` | Pan gesture offset tracking |
| `scaleFactor=`, `scaleGestureFactor=` | Gesture scale factor fields |
| `GuidanceFollowMode` | Follow mode enum/type in navigation |
| `PolylineViewportIntegrityBridge$GuidanceFollowMode` | Follow mode in polyline viewport integrity |

### Google Maps gesture patterns (inferred)

Based on the extracted strings and general knowledge of Google Maps:

1. **Pinch-to-zoom anchored at centroid** — the map point under the two-finger centroid stays fixed during zoom (same as our implementation)
2. **Two-finger rotation anchored at centroid** — `RotateTouch` indicates rotation gesture handling centered on the touch points
3. **Follow-mode exit on user gesture** — `GuidanceFollowMode` and viewport integrity tracking suggest follow mode exits when the user gestures, then can re-engage
4. **Drag/pan gesture end detection** — `DragGesture finished but pointer_id` shows explicit gesture end tracking (we use pointer-up detection)
5. **Camera mode validation** — Follow vs Override modes with rotation constraints in follow mode

---

## 3. Comparison: Our App vs Google Maps

| Aspect | Google Maps (inferred) | RideTogether (current) | Gap |
|--------|----------------------|----------------------|-----|
| Pinch-to-zoom anchoring | Centroid-anchored (point under fingers stays put) | Centroid-anchored via `ZoomAnchorMath.anchoredCenterWithRotation()` | ✅ Match |
| Two-finger rotation anchoring | Centroid-anchored (`RotateTouch`) | Centroid-anchored via `anchoredCenterWithRotation()` | ✅ Match |
| Follow-mode exit on gesture | Yes (follow mode exits on pan/pinch) | Yes (micro-pan threshold + rotation exits follow) | ✅ Match |
| Follow-mode zoom-only | Stays in follow mode (rider anchored) | Yes (`setZoom` keeps follow mode) | ✅ Match |
| Gesture end detection | Explicit pointer tracking (`DragGesture finished`) | Pointer-up detection via `awaitPointerEvent(Final)` | ✅ Match |
| Tilt during gestures | 30-45° in follow mode | `tiltCos` supported in anchor math | ⚠️ Partial — tilt not adjusted during gestures |
| Velocity-based continuation (fling) | Likely present | Not implemented | ❌ Missing |
| Rotation threshold | Unknown (likely smooth) | 0.05° (fixed in BUG-03) | ✅ Good |
| Pinch zoom threshold | Unknown | `PINCH_ZOOM_DELTA_LOG2 = 0.005f` | ✅ Reasonable |

### What our app does well (matching Google Maps)
1. **Centroid-anchored zoom + rotation** — The map point under the pinch fingers stays fixed, matching Google Maps' core gesture contract
2. **Follow-mode awareness** — Zoom-only in follow mode keeps the rider anchored; rotation or deliberate pan exits follow mode
3. **Fractional zoom handling** — `scaledTileSizePx` used in anchor math (fixed in BUG-02/BUG-06)
4. **Gesture end detection** — Pointer-up tracking clears `gestureInProgress` flag

### Improvements needed

1. **Velocity-based gesture continuation (fling)** — Google Maps likely continues map motion after fingers lift based on gesture velocity. Our app stops immediately on pointer up. Consider adding a fling/decay animation after pinch/pan gestures end.

2. **Tilt adjustment during gestures** — Google Maps adjusts tilt (30-45° in follow mode) during camera transformations. Our app supports `tiltCos` in anchor math but doesn't dynamically change tilt during gestures. Consider adding tilt transitions on follow-mode exit/entry.

3. **More sophisticated follow-mode re-entry** — Google Maps may re-enter follow mode after a period of no user interaction. Our app requires explicit toggle (`toggleFollowRiderMode()`). Consider auto-re-entering follow mode when the rider approaches the route or after idle time.

4. **Two-finger pan vs pinch discrimination** — Our `PINCH_ZOOM_DELTA_LOG2 = 0.005f` threshold distinguishes pinch from two-finger pan. Google Maps may use more sophisticated discrimination (e.g., finger spread vs parallel movement).

---

## 4. Limitations

- **No rea providers available** — Ghidra, Hopper, IDA, and Kuna are not configured. Deep binary analysis (decompilation, call graphs, xrefs, symbol resolution) on `libgmm-jni.so`, `libgoogle3.so`, and `libelements.so` is not possible.
- **`libgmm-jni.so` is stripped** — Only 18KB, no usable symbols or strings. The real gesture logic is likely inside `libgoogle3.so` (36MB) but cannot be decompiled without a provider.
- **String extraction only** — The gesture-related strings found are hints, not definitive evidence of implementation details. Some strings (e.g., from `libelements.so`) are from unrelated subsystems (Yoga layout, YouTube elements).
- **No device testing** — adb input taps/swipes produce no screen changes on the connected device (RMX3360), so gesture behavior cannot be verified on-device.

---

## 5. Recommended Next Steps

1. **Configure rea providers** (Ghidra 12.1.x or Kuna 1.833) to enable deep binary analysis of `libgoogle3.so` for gesture function identification
2. **Implement velocity-based gesture continuation** (fling) after pinch/pan gestures
3. **Add dynamic tilt transitions** during follow-mode exit/entry
4. **Consider auto-re-entry to follow mode** after idle time or route proximity
5. **On-device gesture testing** — resolve adb input permission issues to test gestures on RMX3360

---

## Files Changed

| File | Change |
|------|--------|
| `app/src/main/java/com/example/MainActivity.kt` | Removed `BackHandler` from `LIVE_RIDE` branch (back handling moved to `LiveRideScreen`) |
| `app/src/main/java/com/example/ui/screens/LiveRideScreen.kt` | Added `BackHandler` import and `BackHandler { showEndRideConfirm = true }` in root `Box` |
| `app/src/main/java/com/example/data/map/tile/TileEntity.kt` | Previously fixed: added `@Index` annotation for `ix_tile_cache_expiration` (Room migration fix) |
