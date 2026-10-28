# Pocket Agent — Android coding agent MVP

Pocket Agent is a Persian-first Android app for coding tasks on the phone. It is a custom Codex-style agent, **not the official Codex application**, and it does not claim parity with all Codex products or the full official Codex harness.

## What is implemented

- Native Kotlin + Jetpack Compose chat UI.
- Configurable HTTPS Chat Completions endpoint, model, and API key; API key encrypted with Android Keystore (AES-GCM).
- Project-folder picker through Android's Storage Access Framework.
- Private working copy: the agent inspects and edits a local copy under the app's private storage. The original selected folder is untouched until the user chooses **Apply changes**.
- Project tools: list files, read text files, literal source search, stage file edits, stage single-file deletions.
- Change review: shows file-level added/edited/deleted changes and a before/after text preview; user can apply or discard the staged changes.
- Native Android device actions: open installed apps and settings, open HTTP(S) links, create alarm/timer requests through Android Clock, and inspect/interact with the currently visible app through an explicitly user-enabled Accessibility service.
- Accessibility UI tools include screen-text inspection, tap by text or coordinates, swipe, enter text in the focused field, scroll, and Android Back/Home/Recents/notification-shade actions. Password fields are excluded from screen text and text entry.
- Approved non-root shell commands run in a private Termux userspace embedded inside Pocket Agent (ARM64 bootstrap, Bash, core utilities, Git, Python and Node.js). The separate Termux app is not required. Root commands remain on the explicit su/KernelSU path. The exact command and reason are shown before execution, with extra warnings for high-risk patterns.
- Multi-step tool-calling loop with a maximum of 12 rounds, bounded file/output sizes, command timeouts, and an automatic context reset when it gets large.
- Persistent chat switcher: each chat stores its own visible transcript and tool-call/model history, so switching chats does not mix their context. A shared `GLOBAL_MEMORY.md` is injected into every chat and can be edited in the app or updated by the agent with concise non-sensitive notes.
- An app-managed Pocket Agent home folder is created on first run under Android's app-specific external storage (with an internal fallback). Workspace snapshots, chat logs, and global memory live there. Terminal executables use the app's private internal storage to avoid shared-storage execution restrictions.
- Terminal tool installation: the agent can prepare shell scripts (shown in a review dialog) or download a binary from HTTPS only when the user approves. Binary downloads require a supplied 64-character SHA-256 checksum, are size-limited, and are rejected on mismatch. Approved tools are available in the embedded shell’s PATH.
- On-demand Android permission requests for camera, microphone, location, photos, and notifications, plus entry points to special-access settings such as Accessibility, all-files access, usage access, overlay, and notification listener. Requests are triggered only when relevant to the task; Android still requires user action for special permissions.
- Basic device info tool (manufacturer/model, Android release, supported ABIs, app-private free storage) without reading IMEI or serial numbers.
- GitHub sync workflow: after reviewing the remote change list, send selected workspace changes to a configured `owner/repo` branch as one Git commit through the GitHub REST API. The token is encrypted at rest with Android Keystore and is never included in model prompts or tool results.
- GitHub Actions workflow to build a debug APK and publish it as an artifact.

## Important limitations

- This is a custom Codex-style MVP, **not the official Codex Android app and not feature parity with Codex**. It does not include official Codex account sign-in, hosted Codex task environments, full GitHub issue/PR/worktree management, parallel-agent orchestration, MCP server management, skills/automations, streaming tokens, or the full official Codex sandbox.
- The model connection uses a provider-compatible Chat Completions API with tool/function calling. Provider compatibility varies. You need an API key and the provider may charge separately.
- Commands run with the app UID unless the user approves a `run_root_command`. The app does not provide a strict OS-enforced sandbox for shell commands; a command can reach beyond the project directory. Review each command carefully, especially root commands.
- File contents, command output, and visible on-screen text explicitly read through Accessibility tools are sent to the configured AI provider for analysis. The Accessibility service does not continuously save screen events, but it can access sensitive visible information when called; enable it only if you trust the app and provider. Password-field text is not returned by the screen reader.
- The source folder copy excludes generated/dependency folders such as `.git`, `node_modules`, `build`, `.gradle`, `dist`, and `target`, and limits workspace import to 10,000 files / 150 MB. It does not import a Git history.
- GitHub sync creates a single commit on the configured existing branch, limited to 40 changed files and 15 MB per push (with a 5 MB per-file limit). It does not create repositories or branches and does not open pull requests. A fine-grained PAT with **Contents: Read and write** permission for the target repository is required. Review the remote diff before sending; API errors or branch conflicts stop the operation. It uploads only changed files; the selected project snapshot should match the starting state of the target branch. It is not a tool for initializing an empty repository or uploading every unchanged file.
- The app only stores the GitHub token encrypted at rest. Anyone who can use the unlocked app and its configured token may be able to push to the configured repository, so prefer a fine-grained token scoped to one repository and revoke it if the device is lost.
- Android does not include all developer tools by default. Pocket Agent embeds a private Termux userspace with Git, Python and Node.js; Gradle or project-specific toolchains may still need separate setup.
- The APK is debug-signed. Do not treat it as a production release.
- All-files access is an optional special permission requested only for tasks that truly need broad filesystem access. Android opens its settings page and the user must enable it manually; ordinary project work continues to use the folder picker.
- Runtime permissions are not granted automatically at install or startup. The agent can request a declared permission when a task needs it; special access pages must be enabled manually by the user. Android does not expose arbitrary private data from other apps merely because this app has Accessibility enabled, and secure apps/system screens may block automation.
- Downloaded executable compatibility depends on Android ABI, linker/API level, and device policies; the installer verifies integrity but does not guarantee that a random binary is compatible or safe. Review the URL/checksum and the command before running installed tools.
- Accessibility-based screen control works only after the user manually enables **Pocket Agent — کنترل گوشی** in Android Settings → Accessibility. Android, system UI, banking/security apps, or apps that draw their interface outside the accessibility tree may restrict some actions. Root commands also require a working `su`/KernelSU grant and explicit approval.
- Alarm and timer requests use Android's standard Clock intents and ask the default Clock app to set the action directly. Since Clock apps vary by device, verify the alarm/timer the first time you test this build.
- GitHub Actions build was verified for earlier revisions. The current phone-control revision needs a fresh CI build; no physical-device functional test has yet been completed.

## Build using GitHub Actions

The project is already configured in [RM007-UI/PAA-POCKET-AGENT-ANDROID](https://github.com/RM007-UI/PAA-POCKET-AGENT-ANDROID). The verified workflow is **Build Pocket Agent APK**. A successful run creates the `pocket-agent-debug-apk` artifact; download the ZIP from the run’s **Artifacts** section and extract `app-debug.apk`.

For future changes, push to `main` (changes under `app/` or build configuration automatically trigger a build) or use **Actions → Build Pocket Agent APK → Run workflow**.

## First run

1. Open the app and tap the folder icon to select a project folder. The app copies project files to its private working copy.
2. Open settings and enter an HTTPS provider base URL (for example `https://openrouter.ai/api/v1`) or a full endpoint ending in `/chat/completions`, a model that supports tool calling, and your API key. The app adds `/chat/completions` when needed.
3. Ask the agent to inspect or modify a specific codebase. It can read and edit the private working copy.
4. Inspect **Changes** and preview individual files. Tap **Apply changes** only after reviewing the changes you want to keep.
5. Commands always require approval. Root commands can access the device with superuser permissions; only approve commands you understand.
6. To control other apps, manually enable **Pocket Agent — کنترل گوشی** from Android Settings → Accessibility. This is a sensitive permission: visible screen text may be sent to your selected AI provider when the agent reads the screen. You can disable the service in Android settings at any time.
7. You can ask the agent to open apps/settings, set an alarm for a clock time (for example 15:00), or start a countdown timer (for example 15 minutes). For app UI interactions, it must read the screen and use Accessibility tools; some apps may block automation.
8. In Settings, optionally configure `owner/repo`, the target branch, and a fine-grained GitHub token. In Changes, inspect the *GitHub changes* file list and preview, then tap **ارسال به GitHub** to create a commit. This push is separate from applying changes to the original SAF folder. The remote branch must already contain the same project baseline for changed-file-only sync to be meaningful.

## Design overview

- `AgentEngine.kt`: model API, agent loop, native Android intent tools and tool routing.
- `PhoneAccessibilityService.kt`: opt-in on-device screen reading and UI interaction.
- `WorkspaceManager.kt`: SAF import, private worktree, separate local/remote baseline snapshots, bounded source operations, diff preview, apply/discard.
- `EmbeddedTermuxRuntime.kt` and `TerminalDialog.kt`: verified bootstrap installation into PAA's private files directory, embedded Bash execution, environment setup and the interactive terminal emulator. `ShellRunner.kt` retains the explicit root-command path.
- `SecurePrefs.kt`: provider settings plus encrypted API-key / GitHub-token storage.
- `GitHubSync.kt`: reviewed, bounded file changes committed through GitHub's Git Database API.
- `MainActivity.kt`: chat, project picker, tool approval, embedded terminal initialization, workspace change review, GitHub push and settings UI.

## Local build

With JDK 17, Android SDK platform 36, Build Tools 36.0.0, and Gradle 8.13 installed, first place the two verified bootstrap assets from the `paa-embedded-termux-bootstrap-v1` GitHub release in `app/src/main/assets/` (`bootstrap-aarch64.zip` and `bootstrap-aarch64.zip.sha256`), then run:

```bash
gradle assembleDebug
```

The APK is produced at `app/build/outputs/apk/debug/app-debug.apk`. The GitHub Actions workflow downloads and verifies these assets automatically.


## Embedded Termux runtime (v0.10.0)

- Pocket Agent contains its own ARM64 Termux userspace, Bash shell, terminal emulator UI and common developer tools. Installing the separate `com.termux` app is not required.
- On first terminal use or the first approved non-root shell command, PAA verifies the bundled bootstrap with SHA-256 and installs it into its private app storage. Later sessions reuse that environment.
- The embedded base includes Termux core utilities plus Git, Python, Node.js, nano, curl, wget and tree, compiled for PAA's own prefix. Do not assume arbitrary packages from the official Termux repository are compatible with this custom prefix; extra packages need a repository built for PAA or an explicit source build.
- Approved non-root commands run as the PAA app UID inside this environment; root commands continue through the explicit su/KernelSU path. Review every command before approving it.
- The current bundled environment targets ARM64 Android devices. The first run needs extra time and private storage to unpack the bootstrap; subsequent terminal launches do not unpack it again.

