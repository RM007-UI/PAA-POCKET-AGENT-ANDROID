# CI smoke test notes

The Android emulator smoke test installs the debug APK, launches
`com.rm007.pocketagent`, checks that the process remains alive, captures the
UI hierarchy and screenshot, and fails on a fresh fatal application crash.

The current x86_64 emulator cannot execute the embedded ARM64 Termux userspace.
A passing emulator smoke test therefore validates APK installation and app
startup only; it does not validate Bash, apt, or pkg inside the app sandbox.
Those require an ARM64 Android runtime/device test.
