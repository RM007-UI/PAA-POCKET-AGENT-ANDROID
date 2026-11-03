# Codex Hybrid for Android — Architecture and Delivery Plan

## Goal

Evolve Pocket Agent into a hybrid Android coding workspace with two explicit execution modes:

1. **On-device mode** — keep Pocket Agent's existing private workspace, embedded ARM64 shell, Git/Python utilities, staged file changes, and review-before-apply flow.
2. **Remote Codex mode** — connect the Android client to a user-operated Codex host through the official Codex app-server protocol over an authenticated encrypted WebSocket connection.

The app must clearly label which mode is active. Remote Codex is not represented as local execution, and the existing provider-compatible agent is retained as a fallback.

## Important compatibility boundary

The official Codex CLI documentation describes supported desktop/server installation paths, not a supported Android installation target. The Android app therefore must not claim that the official Codex CLI has been ported or that Android has official Codex support.

The remote mode uses the documented Codex app-server interface hosted on a supported machine. A later experimental Android-native CLI build may be investigated separately, but it must not block the first hybrid release or be presented as supported until it passes device tests.

## Existing foundation

The current Pocket Agent app already provides:
- Kotlin + Jetpack Compose UI.
- Private project workspace imported through Android's Storage Access Framework.
- Staged edits/deletions with preview and explicit apply/discard.
- Embedded ARM64 Termux userspace and terminal.
- Provider-compatible tool-calling agent.
- Encrypted API credentials and GitHub sync.
- GitHub Actions APK build.

These capabilities remain intact. Remote Codex integration must not silently bypass workspace review or existing user approvals.

## Proposed architecture

### Android client
- **Mode selector:** On-device Agent / Remote Codex.
- **Remote connection settings:** WSS endpoint, pairing/auth token, connection test, disconnect/revoke.
- **Codex transport:** WebSocket client implementing JSON-RPC framing and app-server initialize/handshake, request IDs, notifications, cancellation, reconnect handling, and protocol-version checks.
- **Conversation UI:** render assistant messages, tool activity, progress, errors, and approval requests as distinct events.
- **Approval UI:** require a clear user decision for any remote action that requests approval. Never auto-approve command execution.
- **Workspace bridge:** explicitly select which project folder/workspace is shared with the remote host. Show the selected root and the fact that project data is sent to that host.
- **Local terminal:** retain the existing embedded shell as a separate local capability.

### Remote host
- Runs a compatible Codex CLI/app-server installation on a supported desktop or server OS.
- Exposes the app-server only through a private, authenticated encrypted tunnel.
- Uses a dedicated low-privilege OS account and a dedicated project directory.
- Keeps Codex sandbox and approval policies enabled; no unrestricted host-wide access by default.
- Does not expose an unauthenticated public WebSocket listener.

### Data and security boundaries
- Never place OpenAI session cookies, OAuth refresh tokens, or API credentials in app logs or model prompts.
- Store pairing credentials encrypted with Android Keystore.
- Mask tokens in settings and diagnostics.
- Require explicit user confirmation before sharing a project or starting a remote session.
- Display remote-host identity and workspace path before sending work.
- Provide disconnect and local credential-revocation controls.
- Do not send unrelated phone screen content or Accessibility data to the remote Codex host.
- Keep local and remote transcripts visibly separated.

## Delivery phases

### Phase 0 — Compatibility and protocol spike
- Confirm current app package, Gradle setup, min/target SDK, and networking dependencies.
- Pin a tested Codex app-server protocol version.
- Build a small protocol test harness against a local development host.
- Verify initialize, thread/session creation, message streaming, cancellation, and approval events.
- Document protocol gaps and unsupported methods.

### Phase 1 — Remote connection foundation
- Add remote endpoint and pairing-token settings.
- Implement secure WebSocket transport and protocol handshake.
- Add connection status, timeout, reconnect, and disconnect behavior.
- Add a developer diagnostics screen with secrets redacted.

### Phase 2 — Remote conversation
- Add Remote Codex mode to the chat UI.
- Stream messages and activity events.
- Support cancellation and session resume where the protocol supports it.
- Show tool/command approval requests and require explicit approval.

### Phase 3 — Project/workspace bridge
- Add explicit project sharing and remote workspace selection.
- Keep the existing local private workspace and staged-change review.
- Add clear sync boundaries and a visible remote/local indicator.
- Verify that remote edits cannot silently overwrite the selected original folder.

### Phase 4 — Hardening and release
- Test reconnects, malformed events, host restarts, token revocation, and protocol mismatch.
- Test on Android 12 / ARM64, including the Redmi Note 9 Pro class of device.
- Run CI, lint, unit tests, and APK assembly.
- Publish a debug APK artifact first; do not label it production-ready until device testing is complete.

## Acceptance criteria

- User can use the existing local agent and embedded terminal without a remote host.
- User can connect to a compatible remote Codex app-server using an authenticated encrypted connection.
- The UI always identifies local versus remote execution.
- Remote approval requests cannot be silently accepted.
- Project sharing is explicit and scoped to the selected workspace.
- Disconnecting removes the active remote session and prevents further requests.
- No credentials appear in logs, crash reports, or chat transcripts.
- Existing apply/discard and GitHub sync flows continue to work.

## References

- OpenAI Codex CLI guide: https://learn.chatgpt.com/docs/codex/cli
- Codex CLI source repository: https://github.com/openai/codex
- Codex CLI command reference (app-server and remote options): https://learn.chatgpt.com/docs/cli/reference

## Status

Planning branch created: `codex-hybrid`.

This document is the implementation contract for the first hybrid milestone. It does not claim that remote transport or an Android-native official Codex binary has already been implemented.
