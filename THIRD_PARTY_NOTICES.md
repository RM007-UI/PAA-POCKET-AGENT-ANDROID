# Third-party notices

## Termux terminal UI

Pocket Agent uses the `com.termux.termux-app:terminal-view:0.118.0` library from the `termux/termux-app` repository, tag `v0.118.0`, to render the in-app terminal. The upstream repository declares GPL-3.0-only, with an Apache-2.0 exception for code derived from Android Terminal Emulator. Review the complete upstream notice and exceptions at:

- https://github.com/termux/termux-app/blob/v0.118.0/LICENSE.md
- https://github.com/termux/termux-app/tree/v0.118.0/terminal-view
- https://github.com/termux/termux-app/tree/v0.118.0/terminal-emulator

## Embedded Termux userspace

The ARM64 bootstrap is built from the open-source Termux package build system at https://github.com/agnostic-apollo/termux-packages/tree/infra-improvs and configured for Pocket Agent's own app-private prefix. It is not the separate `com.termux` application. Packages retain their own license and copyright files in the embedded prefix under `share/doc/`; each included package's license applies to that package.

The bootstrap build recipe and the changes made to build it for Pocket Agent are documented in `.github/workflows/embedded-termux-bootstrap.yml`. The bootstrap is checksummed before it is bundled into the APK.
