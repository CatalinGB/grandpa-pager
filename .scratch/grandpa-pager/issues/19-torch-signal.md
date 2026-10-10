# 19 — Torch/flash attention signal

Type: prototype
Status: resolved
Blocked by: 14

## Question

Can the receiver tap into the phone's torch to signal a new message — ideally a
**slow fade in/out, 3 times**?

## Answer

Research: `.scratch/grandpa-pager/research/torch-signal.md`.

**A fade is not possible on Android 9.** The torch LED is binary; torch
*strength*/dimming (`setTorchStrengthLevel`, `FLASH_INFO_STRENGTH_MAXIMUM_LEVEL`)
is API 33+ and needs hardware support. On API 28 the only control is on/off.

So the achievable signal is a **slow 3× on/off pulse** (ON 1500 ms / OFF 1500 ms,
≈9 s total), fired once with the glow/chime/vibration on arrival.

Implementation (`ReceiverActivity`):
- `CameraManager.setTorchMode(cameraId, on)` on the camera whose
  `FLASH_INFO_AVAILABLE` is true (camera 0 here). **No permission required.**
- `playTorchCue()` posts an on→off chain 3×; `onDestroy` cancels and forces the
  torch off.

Verified on the Mi A1: `torch(true/false) ok on camera 0` ×3 with the expected
timing. Behaviour matches the rest of the cue: fired once, never loops.
