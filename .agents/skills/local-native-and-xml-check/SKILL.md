---
name: local-native-and-xml-check
description: Compile the repository's C/C++/JNI sources locally with gcc/g++ and PyPI ziglang for x86_64 and aarch64 (absolute -I paths, full -c objects), and validate every Android XML/manifest reference against the repo-wide resource union. Use before finalizing native or resource changes, and as the XML gate after editing res/ or layouts.
---

# Local native & XML validation gate

## When to use

- After editing anything under `res/`, any `AndroidManifest.xml`, or any `.c/.cc/.cpp/.cxx`.
- As the resource-reference gate before merging or opening/updating PRs.

## XML / manifest validation (runs anywhere)

```bash
python3 .agents/skills/local-native-and-xml-check/scripts/validate_android_xml.py
```

- Parses all `res/**/*.xml` in `core/app` + `core/app/src/main/AndroidManifest.xml`
  (ElementTree) — must be 0 malformed.
- Scans every `@type/name` attribute with attribute-aware resolution; `tools:` attributes and
  `@android:` refs are ignored; `@+id/` are definitions.
- Resolves against the union of all 22 repository resource roots (`values*`, `layout*/drawable*/`
  file names, `@+id/` definitions).
- Exit 0 = pass. The only tolerated unresolved references are the Material-library ones
  (see `changes.md`, "late 4" — 41 refs, all `Widget.Material3.*`/`TextAppearance.Material3.*`/
  `ShapeAppearance.Material3.*`/`ThemeOverlay.MaterialComponents.*`/library string id).
  Anything else is a hard failure.

## C / C++ / JNI compile (feature touches none of these, but the bar is standing)

Toolchain: system `gcc`/`g++` (Debian 12, 12.2.0) and PyPI `ziglang` (`python-zig`, clang 22.x):

```bash
python3 -m venv /tmp/zvenv && /tmp/zvenv/bin/pip install ziglang cppcheck-py clang-tidy
JNI=/tmp/ktnpm/node_modules/javajre-linux-64/jre/include  # npm JRE ships jni.h/jni_md.h
gcc -c -O2 -I "$JNI" -I "$JNI/linux" termux/application/src/main/cpp/termux-bootstrap.c -o /tmp/termux-bootstrap.o
gcc -c -O2 -I "$JNI" -I "$JNI/linux" termux/emulator/src/main/jni/termux.c -o /tmp/termux.o
g++ -c -O2 -I "$JNI" -I "$JNI/linux" -I /tmp/ndk_stub termux/shared/src/main/cpp/local-socket.cpp -o /tmp/local-socket.o
/tmp/zvenv/bin/python-zig cc -target aarch64-linux-gnu -D_GNU_SOURCE -O2 -I "$JNI" -I "$JNI/linux" -c termux/emulator/src/main/jni/termux.c -o /tmp/termux-arm.o
```

Notes:

- `python-zig` needs absolute `-I` and full `-c` compilation (no `-fsyntax-only`).
- `/tmp/ndk_stub/android/log.h` is a hand-written stand-in — the NDK cannot be fetched
  (see Environment.md). No real NDK headers are fabricated.
- GameActivity sample C++ (`core/app/src/main/assets/GameActivity/native/cpp/`) is blocked
  offline on NDK-only headers (EGL/GLES3/`android/asset_manager.h`/`imagedecoder.h`/
  `game-activity/*`); `AndroidOut.cpp` compiles. Do not fake those headers.
- Clean `cppcheck --enable=warning` for the two JNI C files is the standing bar.

## What it proves — and what it does not

XML validation proves the app's own resource graph is closed (0 non-library missing
references), independent of build tooling. The C compile proves JNI translation units are
well-formed for glibc targets; it does not produce an Android `.so` (no NDK).
