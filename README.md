# Pocket Agent — Android coding agent MVP

Pocket Agent is a Persian-first Android app for coding tasks on the phone. It is a custom Codex-style agent, **not the official Codex application**, and it does not claim parity with all Codex products or the full official Codex harness.

## What is implemented

- Native Kotlin + Jetpack Compose chat UI.
- Configurable HTTPS Chat Completions endpoint, model, and API key; API key encrypted with Android Keystore (AES-GCM).
- Project-folder picker through Android's Storage Access Framework.
- Private working copy: the agent inspects and edits a local copy under the app's private storage. The original selected folder is untouched until the user chooses **Apply changes**.
- Project tools: list files, read text files, literal source search, stage file edits, stage single-file deletions.
- Change review: shows file-level added/edited/deleted changes and a before/after text preview; user can apply or discard the staged changes.
- Normal shell and `su`/KernelSU command tools. The exact command and the agent's reason are shown for user approval. High-risk command patterns show extra warnings.
- Multi-step tool-calling loop with a maximum of 12 rounds, bounded file/output sizes, command timeouts, and an automatic context reset when it gets large.
- GitHub sync workflow: after reviewing the remote change list, send selected workspace changes to a configured `owner/repo` branch as one Git commit through the GitHub REST API. The token is encrypted at rest with Android Keystore and is never included in model prompts or tool results.
- GitHub Actions workflow to build a debug APK and publish it as an artifact.

## Important limitations

- This is a custom Codex-style MVP, **not the official Codex Android app and not feature parity with Codex**. It does not include official Codex account sign-in, hosted Codex task environments, full GitHub issue/PR/worktree management, parallel-agent orchestration, MCP server management, skills/automations, streaming tokens, or the full official Codex sandbox.
- The model connection uses a provider-compatible Chat Completions API with tool/function calling. Provider compatibility varies. You need an API key and the provider may charge separately.
- Commands run with the app UID unless the user approves a `run_root_command`. The app does not provide a strict OS-enforced sandbox for shell commands; a command can reach beyond the project directory. Review each command carefully, especially root commands.
- File contents and command output returned as tool results are sent to the configured AI provider for analysis. Do not select private folders or send secrets unless you trust that provider.
- The source folder copy excludes generated/dependency folders such as `.git`, `node_modules`, `build`, `.gradle`, `dist`, and `target`, and limits workspace import to 10,000 files / 150 MB. It does not import a Git history.
- GitHub sync creates a single commit on the configured existing branch, limited to 40 changed files and 15 MB per push (with a 5 MB per-file limit). It does not create repositories or branches and does not open pull requests. A fine-grained PAT with **Contents: Read and write** permission for the target repository is required. Review the remote diff before sending; API errors or branch conflicts stop the operation. It uploads only changed files; the selected project snapshot should match the starting state of the target branch. It is not a tool for initializing an empty repository or uploading every unchanged file.
- The app only stores the GitHub token encrypted at rest. Anyone who can use the unlocked app and its configured token may be able to push to the configured repository, so prefer a fine-grained token scoped to one repository and revoke it if the device is lost.
- Android does not include all developer tools by default. `git`, Gradle, Python, Node, or project-specific toolchains may be missing; a corresponding GitHub Actions workflow is the intended remote build path.
- The APK is debug-signed. Do not treat it as a production release.
- A successful GitHub Actions build and on-device testing have not been confirmed from this workspace yet.

## Build using GitHub Actions from a phone

### Option A — upload the extracted project tree

1. Create a new repository under your own GitHub account.
2. Upload the **contents** of this project (the `app/`, `.github/`, and Gradle files) to the repository root.
3. Open **Actions → Build Pocket Agent APK → Run workflow** or push a change to `main`.
4. After a successful run, download the `pocket-agent-debug-apk` artifact. Unzip it and install `app-debug.apk` on your phone.

### Option B — upload one ZIP

1. Create a new empty GitHub repository.
2. Upload `pocket-agent-android.zip` to the repository root.
3. Create `.github/workflows/build-from-archive.yml` and paste the supplied `build-from-archive.yml` workflow into it.
4. Open **Actions → Build Pocket Agent from ZIP → Run workflow**. Download the `pocket-agent-debug-apk` artifact from a successful run.

## First run

1. Open the app and tap the folder icon to select a project folder. The app copies project files to its private working copy.
2. Open settings and enter an HTTPS endpoint ending in `/chat/completions`, a model that supports tool calling, and your API key.
3. Ask the agent to inspect or modify a specific codebase. It can read and edit the private working copy.
4. Inspect **Changes** and preview individual files. Tap **Apply changes** only after reviewing the changes you want to keep.
5. Commands always require approval. Root commands can access the device with superuser permissions; only approve commands you understand.
6. In Settings, optionally configure `owner/repo`, the target branch, and a fine-grained GitHub token. In Changes, inspect the *GitHub changes* file list and preview, then tap **ارسال به GitHub** to create a commit. This push is separate from applying changes to the original SAF folder. The remote branch must already contain the same project baseline for changed-file-only sync to be meaningful.

## Design overview

- `AgentEngine.kt`: model API, agent loop, tool schemas and tool routing.
- `WorkspaceManager.kt`: SAF import, private worktree, separate local/remote baseline snapshots, bounded source operations, diff preview, apply/discard.
- `ShellRunner.kt`: bounded shell/root command execution and high-risk command heuristics.
- `SecurePrefs.kt`: provider settings plus encrypted API-key / GitHub-token storage.
- `GitHubSync.kt`: reviewed, bounded file changes committed through GitHub's Git Database API.
- `MainActivity.kt`: chat, project picker, tool approval, local/remote change review, GitHub push and settings UI.

## Local build

With JDK 17, Android SDK platform 36, Build Tools 36.0.0, and Gradle 8.13 installed:

```bash
gradle assembleDebug
```

The APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.
