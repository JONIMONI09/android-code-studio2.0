# Rule: Sandbox egress, persistence & verification receipts

## Network reality (measured 2026-11-27, Debian 12 ArenaAI)

Reachable:
- `github.com`, `codeload.github.com` (git + source tarballs)
- `registry.npmjs.org` (npm)
- `pypi.org`, `files.pythonhosted.org` (pip)

Blocked (TLS handshake terminated / connection reset; do not attempt):
- `dl.google.com` (Android SDK/cmdline-tools/NDK/platforms)
- `services.gradle.org`, `gradle.org`, `plugins.gradle.org` (Gradle distribution + plugins)
- `repo.maven.apache.org`, `maven.google.com` (dependency AARs)
- `api.adoptium.net` (JDK installs), `deb.debian.org`
- `release-assets.githubusercontent.com`, `objects.githubusercontent.com` (release binaries)
- `storage.googleapis.com` (agy artifacts)

Consequences: no Android SDK, no Gradle distribution, no full JDK, no library AARs.
GitHub Actions is unregistered/disabled for this fork — never route checks through CI,
never edit `.github/workflows/`.

## /tmp persistence

`/tmp` is wiped between sessions. Toolchains (`/tmp/ktnpm`, `/tmp/zvenv`), stub trees
(`/tmp/kt_stubs`) and outputs (`/tmp/kout_*`) are ephemeral — recreate them with the
scripts in `.agents/skills/`. **Nothing from `/tmp` is ever committed.**

## Mandatory receipts

1. Every verification claim in `changes.md` names the exact command and its exit code.
2. Every *new* environment measurement (network, toolchain, versions) goes into
   `Environment.md` as a dated section appended at the end — see
   `.agents/skills/environment-ledger/SKILL.md`.
3. Language discipline: "type-checks against stubs" is not "builds"; "XML validated" is not
   "resource-complete R". State what was actually proven and what was not.

## Escalation

If a check cannot pass in-sandbox (needs SDK/Gradle/device), document the blocked command
and its exact failure output; never substitute a weaker claim. Full builds happen only on
an SDK machine / arm64 device.
