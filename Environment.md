# Environment.md — Local build & verification recipe (this repository)

_Sandbox: Debian 12 (ArenaAI). Date of this entry: 2026-11-27. Added 2026-11-27 by the
local-verification session; the repo previously had no `Environment.md`._

## Network reality (measured, not assumed)

Reachable:  `github.com`, `codeload.github.com`, `registry.npmjs.org`, `pypi.org`,
`files.pythonhosted.org`.
Blocked (TLS handshake terminated / connection cut): `dl.google.com`, `services.gradle.org`,
`plugins.gradle.org`, `repo.maven.apache.org`, `maven.google.com`, `api.adoptium.net`,
`release-assets.githubusercontent.com`, `objects.githubusercontent.com`, `storage.googleapis.com`,
`deb.debian.org`, `gradle.org`.
Consequence: **No Android SDK, no Gradle distribution, no dependency AARs, no full JDK.**
GitHub Actions is unregistered/disabled for this fork. All builds below are local and do not
involve CI in any way; `.github/workflows` is intentionally untouched.

## One-time bootstrap (this exact session)

    git submodule update --init --recursive   # logback-android — OK

    # Kotlin compiler + JRE 17, both from registry.npmjs.org
    mkdir -p /tmp/ktnpm
    npm --prefix /tmp/ktnpm install kotlin-compiler@2.4.21 javajre-linux-64@17.0.8
    export PATH="/tmp/ktnpm/node_modules/javajre-linux-64/jre/bin:$PATH"
    /tmp/ktnpm/node_modules/kotlin-compiler/bin/kotlinc -version
    # -> info: kotlinc-jvm 2.4.21 (JRE 17.0.8+9-LTS-211)

    # Native toolchain: gcc/g++ 12.2.0 are system-provided; zig/cmake/ninja/cppcheck/clang-tidy via PyPI
    python3 -m venv /tmp/zvenv && /tmp/zvenv/bin/pip install ziglang cmake ninja cppcheck-py clang-tidy
    # ziglang 0.17.1 (clang 22.1.8); cppcheck 2.22.0; clang-tidy LLVM 23.1.4
    # NOTE: the binary is `python-zig`; pass absolute -I paths; use `-c out.o`, not `-fsyntax-only`.

`/tmp` is wiped between sessions — recreate the above as needed. Nothing under `/tmp` is or
may be committed to git (all stub/R/verification output lives in `/tmp` only).

## 1) Stub-classpath Kotlin type-check (framework-free substitute for android.jar)

Full-module (`core/app`) stub-compilation is **not** feasible offline: the module depends on
sibling modules (`core/common`, `core/resources`, `utilities/*`, `termux/*`) and on
Google/AndroidX/Material AARs, none of which are fetchable.

What is feasible and was executed: type-check the **feature scope** against minimal stubs.

1. Generate `R.kt` procedurally from the real resources (union of all 22 `*/src/main/res*`
   roots; ids from `res/values*` + `res/{layout,menu,xml,drawable}*` file names + `@+id/`):
   see changes.md "late 4" for the generator sketch (Python + ElementTree).
2. Write framework stubs for every external symbol referenced (android.*, androidx.lifecycle/
   appcompat, Material, kotlinx-coroutines(+flow/sync), org.json, commons-compress, termux).
   Stubs live **only** in `/tmp/kt_stubs/` — never commit them.
3. Compile:

       export PATH="/tmp/ktnpm/node_modules/javajre-linux-64/jre/bin:$PATH"
       STD=/tmp/ktnpm/node_modules/kotlin-compiler/lib/kotlin-stdlib.jar
       kotlinc -jvm-target 17 -no-stdlib -classpath "$STD" -d /tmp/kout_harness \
         $(ls /tmp/kt_stubs/*.kt) \
         core/app/src/main/java/com/tom/rv2ide/artificial/harness/*.kt \
         core/app/src/main/java/com/tom/rv2ide/artificial/agents/harness/*.kt \
         core/app/src/main/java/com/tom/rv2ide/artificial/agents/Agents.kt \
         core/app/src/main/java/com/tom/rv2ide/artificial/secrets/ApiKey.kt
       # -> exit 0 (warnings only)

Read this honestly: it proves the feature's Kotlin is well-formed against the API surface it
uses. It is NOT a substitute for `assembleDebug` against the real android.jar.

## 2) C / C++ / JNI

    JNI=/tmp/ktnpm/node_modules/javajre-linux-64/jre/include   # npm JRE ships jni.h/jni_md.h
    gcc -c -O2 -I "$JNI" -I "$JNI/linux" termux/application/src/main/cpp/termux-bootstrap.c -o /tmp/c_out/termux-bootstrap.o
    gcc -c -O2 -I "$JNI" -I "$JNI/linux" termux/emulator/src/main/jni/termux.c -o /tmp/c_out/termux.o
    g++ -c -O2 -I "$JNI" -I "$JNI/linux" -I /tmp/ndk_stub termux/shared/src/main/cpp/local-socket.cpp -o /tmp/c_out/local-socket.o
    /tmp/zvenv/bin/python-zig cc -target aarch64-linux-gnu -D_GNU_SOURCE -O2 -I "$JNI" -I "$JNI/linux" -c termux/emulator/src/main/jni/termux.c -o /tmp/c_out/termux-aarch64-linux-gnu.o

- `/tmp/ndk_stub/android/log.h` is a tiny hand-written stand-in (no NDK reachable).
- termux.c needs `-D_GNU_SOURCE` under clang for `ptsname_r`.
- GameActivity sample C++ (`core/app/src/main/assets/GameActivity/native/cpp/`):
  `AndroidOut.cpp` compiles; the remaining 5 files need NDK-only headers (EGL, GLES3,
  `android/asset_manager.h`, `android/imagedecoder.h`, `game-activity/...`,
  `android_native_app_glue.h`) that no reachable host provides.
- `cppcheck --enable=warning` clean for the two JNI C files; `local-socket.cpp` has two
  pre-existing upstream cast warnings (vendored Termux source).

## 3) Resources / manifest / XML validation

Attribute-aware ElementTree scan (skip `tools:` attributes and `@android:` refs):

- 244 XML files parsed, 0 malformed.
- 813 references scanned in `core/app`; 41 unresolved — **all** Material-library resources
  (unreachable AAR); zero non-library unresolved references.

## 4) Docs & PR hygiene

- `changes.md` is the mandatory living record; add a dated entry per session.
- `local.properties` is git-ignored (lines 7/38); never create/commit one.
- `git diff --name-only 24cf7fe..HEAD` lists the 20 files of this feature; keep them free of
  `TODO`/`FIXME`/`XXX` (verified clean 2026-11-27).
- PR #1 must stay open until a real `assembleDebug` + arm64 on-device smoke test passes.

## What only a machine with Android SDK can do

`./gradlew assembleDebug` (needs `gradle-*-bin.zip` from services.gradle.org and the Android
platform/build-tools from dl.google.com — both blocked here). Any claim of a complete local
APK build in this sandbox is false until that network exists.
