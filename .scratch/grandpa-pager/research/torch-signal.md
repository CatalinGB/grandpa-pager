# Torch (rear flash) as an attention signal — Research

**Date:** Oct 2026
**Device:** Xiaomi Mi A1 (Android One, stock), Android 9 / API 28. Rear camera:
dual 12 MP with **dual-LED (dual-tone) flash** (Wikipedia spec sheet). Because the
Mi A1 is Android **One**, the AOSP `CameraService` path below is the one that
applies — there is no MIUI torch gate to worry about.
**Question:** can we add a **slow fade in/out, 3×** torch signal, or only on/off?

All claims are traced to primary sources: the Android API reference, the AOSP
Android 9 (`android-9.0.0_r1`) source for `CameraManager` / `CameraService` /
`CameraFlashlight`, and the Mi A1 spec sheet. Uncertainties are at the end.

---

## Verdict (short)

- **Fading: NO.** There is **no public API on API 28 (or in the AOSP Android 9
  stack) that can dim the torch LED.** The only dimming API,
  `CameraManager.turnOnTorchWithStrengthLevel()`, is **API 33+** (and needs
  hardware with max strength level > 1). `setTorchStrengthLevel` does not exist.
  The torch is **binary on/off only** on this device.
- **The exact call:** `CameraManager.setTorchMode(cameraId, true|false)` — the
  standalone torch, **without opening the camera**. Added in **API 23**.
- **Permission: NONE.** `setTorchMode` needs **no `CAMERA` permission** and no
  runtime prompt on Android 9 (verified in the API reference — no
  `@RequiresPermission` — and in AOSP `CameraService::setTorchMode`, which only
  checks the camera ID, availability and flash presence, not permission).
- **Best achievable 3× pattern:** three **slow binary pulses**, e.g.
  **ON 500 ms / OFF 700 ms, repeated 3×** (≈ 3.6 s total), fired once on arrival.
  Cannot be made to fade; "slow" is expressed by longer ON times + gentle spacing.

---

## 1. Torch control API — `setTorchMode`

**Signature (API 23+):**
```java
CameraManager.setTorchMode(String cameraId, boolean enabled)
    throws CameraAccessException
```

Primary source, verbatim from the reference and AOSP
(`CameraManager.setTorchMode`, added API 23, no `@RequiresPermission`):

> "Set the flash unit's torch mode of the camera of the given ID **without
> opening the camera device**."

- **Works without opening the camera: yes** — this is the documented purpose of
  the API. It talks to the torch/flashlight path in `CameraService` directly,
  not through a `CameraDevice` session.
- **Permission: none.** The reference page for `setTorchMode` carries **no
  "Requires Manifest.permission.CAMERA"** note. In AOSP the `@RequiresPermission(
  android.Manifest.permission.CAMERA)` annotations appear **only on `openCamera`**
  (`CameraManager.java` lines 465 & 502); `setTorchMode` (line 584) has none.
  Native-side, `CameraService::setTorchMode` (Android 9,
  `CameraService.cpp:1433`) never calls `checkPermission(...CAMERA...)`; the
  "android.permission.CAMERA" check lives in `CameraService::connect`
  (`CameraService.cpp:~900`) which is the *open-camera* path. The AOSP
  `Manifest.permission` list no longer even documents a `FLASHLIGHT` permission.
- **OEM (Xiaomi) restrictions:** the Mi A1 is **Android One / stock AOSP**, so the
  AOSP behavior above is what runs; there is no MIUI-specific torch block. The
  only generic gates are the AOSP ones (below): camera-in-use, insufficient
  resources, and process death.

**Finding the camera with a flash** (do not hard-code index 0):
```java
CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
String torchId = null;
for (String id : cm.getCameraIdList()) {
    Boolean has = cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
    if (Boolean.TRUE.equals(has)) { torchId = id; break; }
}
cm.setTorchMode(torchId, true);
```

**Error semantics** (reference + AOSP):
- `IllegalArgumentException` if `cameraId` is null/unknown **or the camera has no
  flash unit** (native: `"Camera \"%s\" does not have a flash unit"`).
- `CameraAccessException.CAMERA_IN_USE` — camera device already in use.
- `CameraAccessException.MAX_CAMERAS_IN_USE` — resources busy.
- `CameraAccessException.CAMERA_DISCONNECTED` — camera service unavailable.

---

## 2. Is a smooth FADE possible? — **No, on API 28**

- The **only** public dimming API is
  `CameraManager.turnOnTorchWithStrengthLevel(String, int)`, **Added in API
  level 33**, gated by
  `CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL`:
  > "If this value is greater than 1, then the device supports controlling the
  > flashlight brightness level… If this value is equal to 1, flashlight
  > brightness control is not supported."
  `FLASH_INFO_STRENGTH_MAXIMUM_LEVEL` / `…_DEFAULT_LEVEL` are themselves
  **API 33**. There is **no `setTorchStrengthLevel` method** (the name in the
  brief does not exist; the real method is `turnOnTorchWithStrengthLevel`).
- `CaptureRequest.FLASH_STRENGTH_LEVEL` (manual flash brightness while a camera
  session is open) is **API 35** — also unavailable.
- On **API 28** the only torch controls are binary: `setTorchMode` (standalone)
  and `CaptureRequest.FLASH_MODE` = `TORCH`/`SINGLE`/`OFF` (API 21; **no
  brightness field** at all). The old deprecated `android.hardware.Camera`
  (camera1) `Parameters.setFlashMode(FLASH_MODE_TORCH)` is likewise binary.
- Emulating a fade by **PWM** (toggling fast enough to fuse visually) is **not
  workable**: each `setTorchMode` is a synchronous Binder call into
  `CameraService` → flash HAL, with the state change reported **asynchronously**
  via `TorchCallback.onTorchModeChanged`. Flicker-fusion needs ≳ 50–60 Hz;
  toggling at that rate reliably is impossible over this path and would be
  visually worse (flicker) at the ~10–20 Hz that might be reachable.
- Any sysfs LED-brightness trick requires root and is out of scope.

**Plain statement: on Android 9 / API 28 the torch cannot be dimmed. Only
on/off is possible.** (Whether the Mi A1 hardware would even expose strength
control on API 33 is untestable here; dual-tone LED flashes usually report
max level = 1, i.e. still binary — but it is moot on API 28.)

---

## 3. What pulse pattern IS achievable (binary), and timing limits

`setTorchMode` has **no documented minimum on/off time** — there is no throttle
or rate limit in `CameraFlashlight::setTorchMode` (Android 9). Constraints are
mechanical, not specified:

- Each toggle is a Binder → `CameraService` → flash HAL round-trip; the new
  state is confirmed **asynchronously** by `TorchCallback`. Firing a new toggle
  before the previous state settles risks errors / a stale `onTorchModeChanged`.
- Practically, leave **≳ 150–200 ms per state** so the HAL callback can settle.

**Achievable, and safe for photosensitivity:** a slow binary blink. WCAG 2.3.1
forbids > 3 flashes/second; a sub-1 Hz pulse is well under that.

**Recommended 3× "slow" pattern** (as close to the requested fade as the
hardware allows):

```
pulse 1:  ON 500 ms  →  OFF 700 ms
pulse 2:  ON 500 ms  →  OFF 700 ms
pulse 3:  ON 500 ms  →  OFF
                        total ≈ 3.6 s,  ≈ 0.8 Hz
```

Fire this **once** on message arrival, time-locked with the existing
chime + glow + vibration (congruent multi-modal cue). Optionally repeat **once**
as a spaced reminder; never loop (habituation / alarm fatigue — see
`elderly-attention-alerts.md`). If a softer feel is wanted, lengthen ON times;
it still cannot fade.

---

## 4. Practical concerns

- **Conflict with the camera / other apps:** the torch is **not exclusive**.
  AOSP docs: "the application does not have the exclusive ownership of the flash
  unit… The torch mode will be turned off and becomes unavailable when the camera
  device… becomes unavailable… other applications are free to call `setTorchMode`
  to turn off the torch." Opening the rear camera (any app) makes torch
  `CAMERA_IN_USE`, and `onTorchModeUnavailable` fires. For a kiosk that does not
  use the camera, this is a non-issue; do not run a camera session concurrently.
- **Lifecycle / foreground service:** the torch stays on as long as the enabling
  process lives. AOSP links the client Binder to death and turns the torch **off
  when the app process dies** (`CameraService::handleTorchClientBinderDied`). A
  **foreground service** (already used by the kiosk) keeps the process alive —
  recommended for reliability. On Android 9 torch is **not** gated by the
  "idle UID" camera restriction (that gate is in `connect`, i.e. opening the
  camera, not in `setTorchMode`).
- **Screen on/off:** independent of the screen. It works with the kiosk screen
  on, and can also run with the screen off. No wake-lock interaction.
- **Battery / thermal:** a camera flash is a high-power LED. Google's own docs
  warn that raising brightness above the default "may make the device more
  likely to reach thermal throttling… or drain the battery quicker." For three
  sub-second pulses this is negligible; avoid long continuous holds.
- **Other togglers:** the user's Quick Settings flashlight tile or any app can
  turn it off mid-pattern. Register `CameraManager.registerTorchCallback` if you
  need to react.

---

## 5. Manifest

- **No permission required** for `setTorchMode`. Do **not** add `CAMERA` for
  torch alone (that would trigger a dangerous runtime prompt for no reason).
  `CAMERA` is only needed if the app ever **opens** the camera.
- Recommended (not required) so the app still installs on flash-less hardware,
  and to document intent:
  ```xml
  <uses-feature android:name="android.hardware.camera.flash" android:required="false" />
  ```
- **Throws if no flash unit:** yes — `IllegalArgumentException` ("the camera
  device doesn't have a flash unit"). Always pick the ID whose
  `FLASH_INFO_AVAILABLE` is true, and wrap the call in `try/catch`.

---

## Uncertainties / caveats

1. **Mi A1 torch strength is untested here.** The API is absent on API 28, so it
   does not matter today; if the phone were ever upgraded past API 33, strength
   control would depend on the HAL reporting max level > 1 — unverified.
2. **No documented minimum on/off interval.** The "≥ 150–200 ms" figure is a
   practical engineering margin derived from the Binder + async-callback design,
   not a spec limit; validate on the device.
3. **Exact flash LED color/temperature** (dual-tone) is not controllable via the
   torch API; both LEDs are driven together as one torch.
4. **Xiaomi add-ons:** Android One on the Mi A1 means no MIUI layer, but a future
   ROM/OEM build could add gating — none is present in the AOSP Android 9 code
   inspected.

---

## Sources

**Android API reference (primary)**
- `CameraManager.setTorchMode` (API 23) —
  https://developer.android.com/reference/android/hardware/camera2/CameraManager#setTorchMode(java.lang.String,%20boolean)
- `CameraManager.turnOnTorchWithStrengthLevel` (API 33) —
  https://developer.android.com/reference/android/hardware/camera2/CameraManager#turnOnTorchWithStrengthLevel(java.lang.String,%20int)
- `CameraManager.getTorchStrengthLevel` (API 33) —
  https://developer.android.com/reference/android/hardware/camera2/CameraManager#getTorchStrengthLevel(java.lang.String)
- `CameraCharacteristics.FLASH_INFO_AVAILABLE` (API 21) —
  https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#FLASH_INFO_AVAILABLE
- `CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL` / `…_DEFAULT_LEVEL`
  (API 33) —
  https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#FLASH_INFO_STRENGTH_MAXIMUM_LEVEL
- `CaptureRequest.FLASH_MODE` (API 21) and `FLASH_STRENGTH_LEVEL` (API 35) —
  https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#FLASH_MODE
- `Manifest.permission.CAMERA` (dangerous; for opening the camera) —
  https://developer.android.com/reference/android/Manifest.permission#CAMERA

**AOSP source (primary), tag `android-9.0.0_r1`**
- `frameworks/base/core/java/android/hardware/camera2/CameraManager.java` —
  `setTorchMode` (line 584, no `@RequiresPermission`); `openCamera` (lines 465,
  502, `@RequiresPermission(CAMERA)`).
  https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-9.0.0_r1/core/java/android/hardware/camera2/CameraManager.java
- `frameworks/av/services/camera/libcameraservice/CameraService.cpp` —
  `setTorchMode` (line 1433, no permission check), `connect` permission +
  idle-UID gate (~line 900), `handleTorchClientBinderDied` (line 2835).
  https://android.googlesource.com/platform/frameworks/av/+/refs/tags/android-9.0.0_r1/services/camera/libcameraservice/CameraService.cpp
- `…/CameraFlashlight.cpp` — `setTorchMode` (line 70), no rate/timing limit;
  `-ENOSYS` when no flashlight.
  https://android.googlesource.com/platform/frameworks/av/+/refs/tags/android-9.0.0_r1/services/camera/libcameraservice/CameraFlashlight.cpp

**Device / background**
- Xiaomi Mi A1 specifications (Android One; Android 9 Pie; dual-LED dual-tone
  flash) — https://en.wikipedia.org/wiki/Xiaomi_Mi_A1
- Companion note: `elderly-attention-alerts.md` (this repo) — WCAG 2.3.1
  (≤ 3 flashes/s), habituation, and "bounded burst, never loop" guidance.
