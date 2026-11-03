# CI smoke test notes

The Android emulator runs as x86_64. The APK bundles both the ARM64 (`aarch64`) and x86_64 Termux bootstraps, and selects the matching runtime from Android's supported ABI list. CI installs the APK, opens the embedded terminal, waits for bootstrap configuration, and runs Bash, `id`, `dpkg --version`, and `pkg --version` under the app UID. It also captures the UI hierarchy, screenshot, and Logcat. A passing x86_64 emulator run validates the x86_64 userspace; ARM64 selection and execution still require an ARM64 device/runtime.
