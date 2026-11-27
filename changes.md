# changes.md — Harness (Experimental) for Agent Mode

Living record of every change this project makes. Newest entries at the top.
All documentation is in English. This file is mandatory and must be updated with
every meaningful change.

---

## 2026-11-27 — glibc runner support + crash-safe logging foundation

**Branch:** `arena/64ecbac3-android-code-studio2-0` (base `dev` @ 24cf7fe)

### What changed

1. **New package `com.tom.rv2ide.artificial.harness`** (all new files):
   - `HarnessLog.kt` — crash-safe, rolling (4 MB × 3), redacting file logger.
     - Synchronous, flushed writes for important events; a session-state marker
       records in-flight harness runs so a crash is detected and reported on next
       launch instead of being lost.
     - Automatic redaction of bearer tokens, `sk-…`, `ghp_…`-style tokens, OAuth
       URL parameters (`code`, `state`, `code_challenge`, `client_secret`,
       `access_token`, `refresh_token`), env-var assignments and registered exact
       secret values.
     - `tail()` / `exportFile()` / `clear()` API for the settings screen export.
   - `HarnessKeystore.kt` — Android Keystore-backed AES-256-GCM secret storage
     (alias `android_code_studio_harness_secret_key_v1`). Secrets never touch
     plain SharedPreferences; values are registered with `HarnessLog` for redaction.
   - `HarnessEnvironment.kt` — single source of truth for harness paths
     (`filesDir/harness/{bin,downloads,checkpoints,sessions}`), Termux prefix/home
     detection, supported ABIs (Claude: arm64-v8a + x86_64; AGY: arm64-v8a only),
     harness preferences (`harness_enabled`, `harness_allow_all`,
     `harness_auto_trust`), IDE project root resolution and project-scope helpers
     (`ideProjectRoot()`, `isInsideIdeProject()`).
   - `HarnessInstaller.kt` — verified download-and-install of the harness binaries.
     - Claude Code: version pinned to npm `dist-tags/latest` of
       `@anthropic-ai/claude-code`; the **platform npm package**
       (`@anthropic-ai/claude-code-linux-arm64` / `-linux-x64`) tarball is
       downloaded and verified against the **publisher-signed `dist.integrity`**
       (SHA-512/SHA-256) before extraction of `package/claude`.
     - Antigravity CLI: version + URL + SHA-512 from the official auto-updater
       manifest (`antigravity-cli-auto-updater-…run.app/manifests/linux_arm64.json`);
       `antigravity` extracted from the tarball.
     - Both installers stage to a temp file, `renameTo()` atomically, and probe
       execution; a failed install **rolls back** and leaves no broken binary.
     - `status()` reports installed/latest versions, Termux bootstrap readiness,
       glibc availability; `resetInstallation()` wipes binaries + markers.
   - `HarnessRunnerSupport.kt` — resolves **how** the glibc-linked official
     binaries execute on-device: cached marker → `grun` (termux glibc-runner) →
     Termux glibc `ld.so` with `--library-path` → direct exec (future-proof if a
     static build ships). Probes candidates with `--version`; result cached in
     `harness/.<binary>-runner`.

2. **`IDEApplication.kt`** (minimal, additive):
   - `HarnessLog.init()` in `attachBaseContext` (earliest possible point).
   - Uncaught-exception handler now records a redacted `HarnessLog.fatal()`
     (with stack trace) before launching the crash-handler activity.

### Key facts (verified 2026-11-27, sandbox)

- Claude Code CLI is `2.1.332` (npm, node >= 22); npm platform tarballs are
  reachable and verified by `dist.integrity`; the binary is a **glibc-linked
  aarch64 ELF** (`PT_INTERP /lib/ld-linux-aarch64.so.1`).
- Antigravity CLI is `1.5.18` (arm64 only); tarball host `storage.googleapis.com`
  is **unreachable from the dev sandbox** (TLS blocked) — on-device egress unverified.
- Termux's answer to glibc binaries is the `termux-glibc` repo
  (`pkg install glibc-repo && pkg install glibc glibc-runner` → `grun`). No PRoot,
  no virtualization: the binaries run natively on the Android kernel.
- The embedded IDE environment (`filesDir/usr` + `filesDir/home`) is a prebuilt
  Termux-style prefix provisioned by the `idesetup` static binary during first
  launch — a full APT bootstrap with `pkg` is **not guaranteed** to exist in it.
- No JDK/Android SDK is available in the dev sandbox — Kotlin compilation of the
  above is **not yet verified**; see "Known limitations".

### Known limitations / open items

- Nothing is wired into the IDE UI yet (settings section, provider dropdown
  entries, auth flows) — those land in follow-up commits.
- `HarnessRunnerSupport` resolver is untested on a real device; the glibc layer
  install path (`pkg`) is unverified for the embedded prefix. Manual fallback:
  open the IDE terminal and run `pkg install glibc-repo -y && pkg install glibc glibc-runner -y`.
- `changes.md` has been created by this commit and must be kept up to date.
- Sandbox egress is restricted; on-device network behavior may differ.

### Rollback / safety

- All changes are additive; no existing IDE classes were modified except
  `IDEApplication.kt` (two try/catch blocks).
- Harness binaries are never executed without passing a checksum verification.

---

## 2026-11-27 (late) — glibc runner, PTY auth, agents, UI wiring, docs

### What changed (all on `arena/64ecbac3-android-code-studio2-0`)

1. **glibc execution support** — `HarnessRunnerSupport.kt` (new): resolves DIRECT / GRUN /
   LDSO invocation for the glibc-linked official binaries (cached marker, `--version`
   probe). No PRoot is used or required — binaries run natively on the Android kernel
   through Termux's glibc layer (`pkg install glibc-repo && pkg install glibc glibc-runner`).
   Installers now roll back when no runner can execute the freshly installed binary.
2. **Claude install channel changed** — tarball now comes from the npm registry
   (`@anthropic-ai/claude-code-linux-arm64|linux-x64`) verified against the publisher
   `dist.integrity` (SHA-512/SHA-256 base64) instead of `downloads.claude.ai`
   (unreachable from the dev sandbox).
3. **Crash-safe logging** — `HarnessLog.kt` gains `fatal()` for the global crash
   handler; built-in redaction patterns fixed (raw-string regexes) and verified
   against sample inputs; secrets read from the Keystore are re-registered for
   redaction after process restarts. `IDEApplication.kt` initializes `HarnessLog` in
   `attachBaseContext` and records a redacted stack trace in the uncaught-exception
   handler before showing the crash screen.
4. **PTY-driven sign-in** — `HarnessPtySession.kt` (new): headless PTY sessions on the
   IDE's own termux-emulator (real TTY; `isRunning`/`exitStatus`; transcript reads with
   full-line joining). `HarnessStreamJson.kt` (new): stream-json parsers for both CLIs
   plus the Google OAuth URL extractor (hard-wrap tolerant) and Claude token extractor.
   `HarnessAuth.kt` (new): the complete interactive flows — Claude `setup-token`
   (URL surfaced; token auto-captured, else paste one-time code/token) and Antigravity
   (auto-select Google OAuth, answer first-run wizard screens, surface the URL, paste
   the one-time code; agy credential file settle detection; **headless `agy models`
   verification before "Connected"**; `/quit` + sign-out support).
5. **Agent implementations** — `agents/harness/HarnessAgents.kt` (new):
   `ClaudeCodeHarnessAgent` and `AntigravityHarnessAgent` (AIAgent interface).
   Project-scoped (fails fast without an open project; IDE-side write guard),
   stream-json event consumption, error classification mapped to the IDE's
   RateLimit/Quota/InsufficientBalance/InvalidApiKey exceptions so the auto-switch
   machinery keeps working, 10-minute watchdog-enforced timeout, NDJSON stdin for agy,
   harness session markers via `HarnessLog.beginSession/endSession`.
   Registered in `AIAgentManager`; model aliases in `Agents.kt`
   (`claude_harness`: sonnet/opus/haiku; `antigravity_harness`: default).
6. **Harness autonomy** — `HarnessAutonomy.kt` (new): merges (preserving foreign keys)
   `~/.claude/settings.json` (`acceptEdits` + `PermissionRequest` hook for
   `Bash|Edit|Write|NotebookEdit`) and `~/.claude.json`
   (`hasTrustDialogAccepted` for the open project). Idempotent; toggle-off restores
   conservative defaults; hook script re-installed on every run.
7. **Settings UI** — new English "Harness (Experimental)" section in AI Preferences
   (`fragment_ai_preferences.xml` + `dialog_harness_auth.xml` + `HarnessSettingsSection.kt`):
   Enable Harness / Allow All Permissions / Auto-Trust toggles, live status block
   (bootstrap, glibc, per-binary install + sign-in state), install/reinstall buttons,
   Connect/Sign-out buttons with full paste-back auth dialog (URL + Open-in-browser +
   code field), Re-check, View/Export/Clear logs, Reset installation. Provider dropdown
   gains "Claude Code (Harness)" and "Antigravity (Harness)".
8. **Docs** — `docs/en/harness.md` (this feature, English, honest limitations);
   `changes.md` created and maintained.

### Verified vs unverified

- Verified in sandbox: npm registry reachability + integrity pipeline, npm tarball
  layout (`package/claude`), ELF headers (glibc, aarch64), parser regexes, XML layout
  well-formedness, brace balance of all sources.
- NOT verified (no Android SDK/JDK in the dev sandbox): Kotlin compilation, on-device
  execution, the embedded prefix's `pkg`/`glibc` availability, Ink TUI wizard
  auto-advancement across agy releases, Claude `setup-token` PTY behavior, end-to-end
  Agent Mode runs. Everything harness must be tested on a physical arm64 device.
- `storage.googleapis.com` (agy artifact host) is unreachable from the dev sandbox
  (TLS); on-device egress unknown — Claude install via npm is the reliable channel.

### Open items

- On-device end-to-end test (install → sign-in → Agent Mode request) on at least one
  arm64 device; per-device glibc provisioning path for prefixes without `pkg`.
- Optional: log export currently shares via `IntentUtils.shareFile` (IDE file provider).

### Static verification pass (same day, after the large write)

- `HarnessPtySession.write()` now feeds raw UTF-8 bytes into
  `TerminalSession.write(byte[], int, int)` — there is no `write(String)` on TerminalSession.
- `HarnessAgents.kt`: added a watchdog thread that SIGKILLs the subprocess after
  10 minutes (the read-loop timeout alone cannot fire on a silent hang); added the
  missing `else -> null` to the `when` over the nullable agy parse result (Kotlin
  requires exhaustive `when` expressions); subprocess is interrupted/waited cleanly
  after the loop.
- `HarnessAuth.kt`: `beginLogin` now resolves the glibc runner on a background thread
  (probes can spawn processes); added a `cancelRequested` flag so a user cancellation
  propagates — in-flight watchers never store a token or code, even if the TUI had
  already printed one; runner probes no longer run on the main thread.
- `Agents.kt`: `ai_agents` now includes the two harness model lists; removed duplicated
  `when` branches in `getProviderForModel`.
- `ApiKey.kt`: `hasAnyApiKey()` additionally returns true when a harness sign-in exists
  (harness agents have no plain-text API key).
- `HarnessInstaller.kt`: dropped an unused `unpackedSize` read from npm metadata.
- `IDEApplication.kt`: line endings normalized back to CRLF (matches file history).
- `docs/en/harness.md` added (feature documentation, English).

Not yet done: Kotlin compilation and on-device end-to-end verification are still pending
— this sandbox has no JDK and cannot reach the Gradle/Android SDK distribution hosts.

### 2026-11-27 (late 2) — glibc auto-install with copyable error report

- New full-width button in the Harness section: **Install glibc Layer**
  (`harnessGlibcInstallButton`). It runs `pkg install -y glibc-repo && pkg install -y glibc glibc-runner`
  inside the IDE's built-in Termux prefix through a hidden PTY (`TERM=dumb`), mirrors the
  last output line into the status text, caps the run at 5 minutes and then refreshes the
  Harness status (re-resolution happens because runner cache markers are dropped first).
- On failure, a dialog shows the full command, exit code and the last 6 000 characters of
  output, with **Copy report** (to the clipboard) and **Retry**. The report is meant for
  the IDE issue tracker — it contains no secrets.
- The claude/agy install failure dialogs gained the same **Copy report** action (message +
  last 4 000 chars of the redacted harness log).
- `HarnessRunnerSupport.clearRunnerCache()` added; `installGlibcLayer` uses it after the
  PTY exits so the next runner probe re-resolves from scratch.
- The sandbox cannot reach `dl.google.com` / `services.gradle.org` / `plugins.gradle.org` /
  `repo.maven.apache.org` (TLS cut), so the Gradle build cannot run here; compile
  verification is delegated to the repository's GitHub Actions workflow
  (`.github/workflows/asm_build.yml`, `workflow_dispatch`, Debug) on the pushed branch.
