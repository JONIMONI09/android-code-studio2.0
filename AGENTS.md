# AGENTS.md — AI agents working in this repository

AndroidIDE (Android Studio–based IDE for Android, Kotlin/AGP fork) — here carrying the
**Harness** experimental feature: Claude Code & Antigravity agents running inside the
built-in Termux prefix, wired into Agent Mode (`core/app/.../artificial/`).

Read this file top to bottom before touching code. It is deliberately short; the heavy
detail lives in the files linked below.

## 1. Session startup protocol

1. Read `Environment.md` first (sandbox network reality + local build/verify recipe +
   receipts), then the tail of `changes.md` (what was done and what was verified).
2. Branch is **fixed**: `arena/64ecbac3-android-code-studio2-0`. Check it out; never
   switch branches, never rename it. Verify `git status` and compare with `origin`:
   `git fetch origin && git log --oneline HEAD..origin/arena/64ecbac3-android-code-studio2-0`.
   If the branch ever appears inconsistent locally, compare content with
   `git hash-object <path>` vs `origin/...` before assuming data loss, then restore with
   `git reset --hard origin/arena/64ecbac3-android-code-studio2-0`.
3. `git submodule update --init --recursive` if
   `composite-builds/external/logback-android` is empty.
4. `/tmp` is wiped between sessions. Recreate toolchains as documented in
   `Environment.md`; never commit anything from `/tmp`.

## 2. Non-negotiable invariants

- **Zero placeholders, zero silent code deletion.** Complete runnable implementations only.
- **No silent deferral to GitHub Actions CI.** The workflow is unregistered/disabled for
  this fork. Never lean on it, never edit `.github/workflows/`. Run every
  sandbox-capable check locally **before** finalizing:
  - Kotlin feature type-check: `.agents/skills/local-kotlin-check/scripts/kotlin_feature_check.py`
  - C/C++ + XML/manifest validation: `.agents/skills/local-native-and-xml-check/scripts/validate_android_xml.py`
- **Honest verification reporting.** Every claim names command + exit code. Distinguish
  "locally verified" from "needs an external Android SDK/Gradle machine"
  (`./gradlew assembleDebug`, on-device arm64 smoke test). Keep PR #1 open until both pass
  on a real device.
- **`local.properties` stays untracked** (git-ignored). Never commit SDK paths, keys or
  machine-local files.
- **Commit only on `arena/64ecbac3-android-code-studio2-0`**, push only there; PRs are
  opened from it.

## 3. Orientation table

| Topic | Where |
| --- | --- |
| Local build/verify recipe + measured receipts | `Environment.md` |
| Session history / per-session verification log | `changes.md` |
| Sandbox egress allowlist + mandatory receipt rule | `.agents/rules/sandbox-and-verification.md` |
| Kotlin/Java/C++/Android resource quality bar | `.agents/rules/android-code-quality.md` |
| Type-check Kotlin without SDK/Gradle (~20 s) | `.agents/skills/local-kotlin-check/` |
| C/C++ (JNI) + XML/Manifest validation | `.agents/skills/local-native-and-xml-check/` |
| Appending dated measurements to Environment.md | `.agents/skills/environment-ledger/` |
| Harness feature documentation | `docs/en/harness.md` |

## 4. Feature map (Harness)

| Concern | Path |
| --- | --- |
| Settings UI (buttons!) + layout | `core/app/src/main/java/com/tom/rv2ide/artificial/harness/HarnessSettingsSection.kt`, `res/layout/fragment_ai_preferences.xml`, `res/layout/dialog_harness_auth.xml` |
| Agent integration | `artificial/agents/harness/HarnessAgents.kt`, `artificial/agents/Agents.kt` |
| Installer / PTY / sign-in / keystore / autonomy | `HarnessInstaller.kt`, `HarnessPtySession.kt`, `HarnessAuth.kt`, `HarnessKeystore.kt`, `HarnessAutonomy.kt`, `HarnessRunnerSupport.kt`, `HarnessStreamJson.kt`, `HarnessEnvironment.kt`, `HarnessLog.kt` |
| Entry point | `artificial/agents/AIAgentManager.kt`, `core/app/src/main/java/com/tom/rv2ide/app/IDEApplication.kt`, `fragments/sidebar/AIPreferencesFragment.kt` |

## 5. What "done" means here

Run the skills, paste real outputs (commands + exit codes) into `changes.md`, update
`Environment.md` only with new measurements (see `environment-ledger`), commit, push,
keep PR #1 open until the SDK/device checks pass.
