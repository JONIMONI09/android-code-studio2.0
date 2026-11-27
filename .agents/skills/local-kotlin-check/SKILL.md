---
name: local-kotlin-check
description: Type-check the Harness feature's Kotlin sources (and any given Kotlin files) inside the sandbox without an Android SDK, Gradle, or a full JDK, using the npm kotlin-compiler package and embedded framework stubs; regenerates a procedural R.kt from the real res/ trees. Use before finalizing any Kotlin change in this repository.
---

# Local Kotlin check (no Android SDK required)

## When to use

- Before committing **any** Kotlin change.
- To catch type/signature/import errors in `core/app/src/main/java/com/tom/rv2ide/artificial/harness/**`,
  `artificial/agents/harness/**`, `artificial/agents/Agents.kt`, `artificial/secrets/ApiKey.kt`.

## What it does

1. Ensures `/tmp/ktnpm` contains npm `kotlin-compiler@2.4.21` and `javajre-linux-64@17.0.8`
   (bootstrap once per session: `npm --prefix /tmp/ktnpm install kotlin-compiler@2.4.21 javajre-linux-64@17.0.8`).
2. Regenerates `/tmp/kt_stubs/R.kt` from the real `res/` union (all 22 `*/src/main/res*` roots;
   ids from `values*/**.xml`, resource file names, `@+id/` occurrences; non-identifier resource
   names are sanitized the same way aapt rewrites `.` → `_`).
3. Writes the embedded framework stub suite (android.*/androidx/material/kotlinx-coroutines/
   org.json/commons-compress/termux/R) into `/tmp/kt_stubs/`. The suite never lives in the repo.
4. Runs `kotlinc -jvm-target 17 -no-stdlib -classpath kotlin-stdlib.jar` over stubs + given files.

## How to run

```bash
python3 .agents/skills/local-kotlin-check/scripts/kotlin_feature_check.py
# PASS/FAIL is printed; exit code == kotlinc exit code.
```

Pass explicit files to check other sets (e.g. after touching new files):

```bash
python3 .agents/skills/local-kotlin-check/scripts/kotlin_feature_check.py <file1.kt> [file2.kt ...]
```

## What it proves — and what it does not

| Claim | Status |
| --- | --- |
| Feature Kotlin compiles against the API surface it uses | **Verified** (exit 0) |
| Resource `R.kt` exists and is generated from real res/ | **Verified** (procedural) |
| Real `android.jar` signature compatibility | **Not proven** (offline stubs can drift) |
| `./gradlew assembleDebug` / APK produced | **Blocked** (needs SDK/Gradle hosts) |
| On-device behavior | **Blocked** (needs arm64 device) |

Expected honest output: `kotlin feature check: PASS` with only benign warnings
(deprecated `URL(String)` constructor, two unnecessary safe calls, one impossible cast in a stub).

## Maintenance

- If the feature imports a new external symbol, add the minimal stub to the embedded
  `STUBS` dict **and** re-run until PASS; keep stubs faithful to real APIs (call the AAR
  signature to mind).
- Never weaken the gate: do not delete sources from the default file list, do not stub
  feature code itself.
