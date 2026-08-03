# Emulator: clicks not registering (drag works, click doesn't)

## Symptom

On the `Pixel_8` AVD, dragging on screen worked, but a plain click (mouse
down+up with no movement) did nothing — no visual response, no launcher
activity change.

## Root cause

The host has a real AMD APU (`AMD Radeon Graphics (RADV RENOIR)`) with a
working Vulkan/Mesa driver, confirmed via:

```
vulkaninfo --summary
```

But `emulator -avd Pixel_8 ...` (no `-gpu` flag) auto-detects GPU support and
hit a driver blocklist, logging:

```
WARNING | Your GPU drivers may have a bug. Switching to software rendering.
...
INFO    | Selecting Vulkan device: llvmpipe (LLVM 21.0.0, 256 bits), Version: 1.4.318
```

It silently fell back to `llvmpipe` (CPU/software Vulkan rendering) instead
of the real GPU. Software rendering is slow enough that the emulator's
frame-based touch dispatch misses short taps (press+release with zero
motion) — the touch event gets coalesced/dropped before a frame renders. A
drag survives because continuous motion events keep re-triggering input
processing.

Confirmed the Android-side touch pipeline itself was fine before diagnosing
render lag — `adb shell input tap x y` successfully switched foreground
activity (opened Files app) even while software-rendering was active.

## Fix

Force the emulator to use the real host GPU, bypassing the blocklist:

```
emulator -avd Pixel_8 -gpu host ...
```

Confirmed in the new boot log:

```
INFO | emuglConfig_get_vulkan_hardware_gpu_support_info: Found physical GPU 'AMD Radeon Graphics (RADV RENOIR)' ...
INFO | Selecting Vulkan device: AMD Radeon Graphics (RADV RENOIR), Version: 1.4.318
```

After relaunching with `-gpu host` and reinstalling/relaunching the app,
clicks registered normally.

`run_emulator.sh` now always passes `-gpu host` when booting the AVD.
