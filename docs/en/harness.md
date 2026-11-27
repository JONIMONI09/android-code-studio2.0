# Harness (Experimental): Claude Code & Antigravity in Agent Mode

> Status: **experimental**. Introduced on branch `arena/64ecbac3-android-code-studio2-0`.
> Everything in this document applies to Android Code Studio only; no other IDE is affected.

## Overview

The **Harness** runs the official agent CLIs on your device, inside the IDE's built-in
Termux-style environment (`filesDir/usr` + `filesDir/home`):

| Agent | Provider id | Binary source | Architectures |
|---|---|---|---|
| Claude Code | `claude_harness` — "Claude Code (Harness)" | npm platform package `@anthropic-ai/claude-code-linux-arm64`/`-linux-x64` (integrity-verified) | arm64-v8a, x86_64 |
| Antigravity CLI | `antigravity_harness` — "Antigravity (Harness)" | Official auto-updater manifest (SHA-512 verified) | arm64-v8a only |

The harness agents appear in the Agent Mode provider dropdown and in
**Settings → AI Preferences → Harness (Experimental)**. They are **not** the same as the
HTTP providers (Gemini/OpenAI/…): they are full agentic CLIs that read, edit and build
your project files themselves, headlessly, on the device.

## Requirements

1. The IDE's first-launch environment setup (`idesetup`) must have completed — the
   embedded Termux prefix must exist (`filesDir/usr/bin`).
2. ~500 MB free storage for the binaries plus work products.
3. Network access.
4. A **glibc compatibility layer** for the official binaries, which are glibc-linked
   (they are not bionic and not static):

   ```sh
   pkg install glibc-repo -y
   pkg install glibc glibc-runner -y
   ```

   Run this in the IDE terminal. If your embedded prefix does not provide `pkg`, the
   **Re-check** button in the Harness section shows the same instructions; without the
   layer the harness installers refuse to mark a binary as usable.

## Installing the CLIs

Open **Settings → AI Preferences → Harness (Experimental)**:

- **Install Claude Code** — resolves the latest version from the npm registry, downloads
  the platform tarball, and verifies it against the publisher-signed `dist.integrity`
  (SHA-512/SHA-256) before installing `package/claude`.
- **Install Antigravity CLI** — reads the official auto-updater manifest, downloads the
  tarball, verifies SHA-512, and extracts `antigravity` as `agy`.

Both installs are staged, atomically renamed into place, probed with `--version`
(through the glibc runner) and rolled back automatically on failure. **Re-check**
re-probes everything; **Re-check** also tells you whether the glibc layer is present.

## Signing in

### Claude Code (Claude subscription)

1. Tap **Connect Claude**. The IDE starts `claude setup-token` in an embedded PTY.
2. Claude prints a sign-in URL. Tap **Open in browser** (or copy the URL into any
   browser on this device) and approve access.
3. The CLI prints a long-lived token (`sk-ant-oat…`). The IDE captures it automatically;
   alternatively paste the **token** (or the **one-time code**, if prompted) into the
   dialog and tap **Submit**.
4. The token is stored in the **Android Keystore** (AES-256-GCM, key never leaves the
   device) and is registered with the log redactor. It is never written to plain
   preferences and never logged.

**Sign out** deletes the token from the Keystore.

### Antigravity CLI (Google account)

1. Tap **Connect Google**. The IDE starts `agy` in an embedded PTY and selects
   **Google OAuth** automatically.
2. agy prints a URL. Tap **Open in browser**, approve access, and copy the **one-time
   code** Google shows you.
3. Paste the code into the dialog and tap **Submit**.
4. agy finishes its first-run setup; the IDE then **verifies the sign-in headlessly**
   with `agy models` (no model call) and only then reports **Connected**.

The OAuth credential is owned by agy at `home/.gemini/antigravity-cli/antigravity-oauth-token`.
The IDE never reads or copies it; **Sign out** deletes it.

## Using the harness in Agent Mode

1. Open a project in the IDE. **The harness refuses to run without an open project.**
2. Settings → AI Preferences → **AI Provider** → choose **Claude Code (Harness)** or
   **Antigravity (Harness)**.
3. Optionally pick a model: `sonnet` / `opus` / `haiku` (Claude Code aliases), or
   `default` for agy.
4. Switch to the Agent Mode chat and type your request.
5. The harness performs the work itself (multi-turn, up to 25 turns / 60 minutes) and the
   IDE shows its final report as a text response. File changes are applied by the CLI
   directly to the project — the IDE does **not** replay them, so IDE-side undo is not
   available for harness edits.

## Autonomy & the project scope

The user-facing switch **Allow All Permissions** (default: on) controls how much the
harness may do without asking:

- **On**: Claude Code runs with `acceptEdits` plus a `PermissionRequest` hook that
  approves `Bash|Edit|Write|NotebookEdit` calls; agy runs with
  `--dangerously-skip-permissions`. Combined with the open project as the working
  directory and a project-scope system instruction, the agent works autonomously on the
  project.
- **Off**: Claude Code reverts to its default permission mode and the hook is removed;
  agy is launched without permission bypass. Headless permission prompts cannot be
  answered, so runs are effectively read-only.

**Project scope** is enforced by: the open project being the subprocess working
directory, a project-scope instruction prepended to every prompt, Claude Code's
workspace trust pre-accepted only for that root (see *Auto-Trust Open Project*), and a
hard IDE-side guard refusing agent file writes outside the project. There is **no
kernel-level sandbox** on Android — do not treat the harness as confined against a
hostile prompt. The environment inside the IDE's Termux (not the rest of the device) is
the boundary.

**Auto-Trust Open Project** (default: on) pre-accepts Claude Code's "Do you trust the
contents of this project?" dialog for the project open in the IDE by merging a single
key into `home/.claude.json`. Turn it off to review the dialog manually on every run.

## Logging & crash safety

The harness maintains its own rolling logs under `filesDir/harness-logs/`:

- 4 MB per file, rotated (`harness.log` → `harness.1.log` … `harness.3.log`).
- Every important event is written synchronously; E/A-level lines call `fd.sync()`.
- A session-state marker is created when a harness run starts and removed when it ends
  cleanly. If the app or the harness process dies unexpectedly, the next start logs a
  diagnostic line instead of losing the crash.
- **Secret redaction**: the Claude token (as soon as it is stored or read), bearer
  tokens, `sk-…`/`ghp_…` tokens, OAuth URL parameters (`code`, `state`,
  `code_challenge`, `client_secret`, `access_token`, `refresh_token`) and env-var
  assignments are masked as `••••` before hitting disk.
- **View logs** / **Export logs** / **Clear logs** in the Harness section. Exports go
  through the IDE's `FileProvider` and are fully redacted.
- The IDE's global uncaught-exception handler also writes a redacted stack trace to the
  harness log before showing the crash screen.

## Troubleshooting

| Symptom | Fix |
|---|---|
| Install fails with "cannot execute: required file not found" on the verify step | The glibc layer is missing — run `pkg install glibc-repo -y && pkg install glibc glibc-runner -y` in the IDE terminal, then **Re-check**. |
| "Install Antigravity CLI" fails on download | The artifact is hosted on `storage.googleapis.com`; check your network egress. |
| "No Google account connected" | Sign in again via **Connect Google**; the sign-in is verified with `agy models`. |
| Claude reports authentication errors | Tap **Connect Claude** and sign in again; then try **Re-check**. The IDE never shows the token. |
| Harness hangs | Runs are capped at 10 minutes. Check **View logs** for the raw stream. |
| Changes didn't happen | Confirm a project is open — the harness refuses to run outside one. |

## Reset & uninstall

- **Reset installation** deletes the binaries and cached state. Sign-ins and logs are kept.
- Full sign-out per provider is available next to each **Connect** button.
- To wipe logs as well: **Clear logs**.
- Nothing outside `filesDir/harness/`, `filesDir/harness-logs/`,
  `filesDir/home/.claude*`, `filesDir/home/.gemini/antigravity-cli/` is touched by the
  Harness feature set.

## Known limitations (honest list)

- Neither CLI ships an Android build; the binaries are glibc ELFs running on the
  Android kernel through the glibc compatibility layer. Behavior on every device, every
  Android version and every seccomp profile cannot be guaranteed.
- The embedded prefix's `pkg` availability is unverified; without it the glibc layer
  must be installed per the manual instructions.
- Antigravity is arm64-only; Claude Code additionally supports x86_64.
- `storage.googleapis.com` (agy artifacts) may be unreachable from some networks.
- TUI screens of both CLIs change between releases; the parsers are forward-compatible
  (unknown events are ignored) but first-run wizard auto-advancement is best-effort.
- The Kotlin code has not been compiled in the development sandbox (no Android SDK
  there); on-device verification is required before this leaves experimental status.
- IDE-side undo does not cover harness edits.
