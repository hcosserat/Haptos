# Haptos

Haptos is a simple Android app that plays audio files with synchronized haptic feedback using `HapticGenerator`.

## Requirements

- Android 12 (API 31) or higher is required for haptic feedback support.
  - Devices running Android 11 or lower are **not supported**.
  - On Android 12+, support depends on your device and OS version.

## Notes

- Haptic feedback is not available when using Bluetooth or wired headphones.
- Haptic feedback might stop if another app or system event get haptic focus (notification, volume slider, ...). Hitting pause/play will reload the generator.
