# Emulator troubleshooting

## Taps don't register (drags do)

The emulator fell back to software rendering (`llvmpipe`) after its GPU driver blocklist
rejected the host GPU (here an AMD APU, `RADV RENOIR`). Software rendering is slow enough that
short taps get dropped while drags survive.

`run_emulator.sh` boots the AVD with `-gpu host`, which bypasses the blocklist. If it happens
anyway, check the emulator boot log: it should say
`Selecting Vulkan device: AMD Radeon Graphics …`, not `llvmpipe`.
