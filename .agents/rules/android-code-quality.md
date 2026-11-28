# Rule: Android code quality bar

## Code

- Complete, runnable implementations. No `TODO` / `FIXME` / `XXX` in files changed by this
  branch (`git diff --name-only 24cf7fe..HEAD` must scan clean).
- No silent deletion of working modules or files; large deletions need explicit maintainer
  approval.
- Match the surrounding code style (CRLF line endings in `IDEApplication.kt`; Kotlin APIs
  consistently; no mixed tabs/spaces unless the file already uses them).
- Do not hand-invent Android/AndroidX APIs: verify against stubs only as a sanity check;
  the source of truth is the real AAR on an SDK machine.

## Resources (`res/`, manifest)

- Attribute-aware validation is a hard gate:
  `python3 .agents/skills/local-native-and-xml-check/scripts/validate_android_xml.py`
  must exit 0. Only Material-library references (`Widget.Material3.*`,
  `TextAppearance.Material3.*`, `ShapeAppearance.Material3.*`,
  `ThemeOverlay.MaterialComponents.*`, library string id
  `appbar_scrolling_view_behavior`) may remain unresolved — the AAR is unreachable in-sandbox.
- IDs in layouts and `@+id/...` occurrences must match code expectations; every
  `findViewById` target must exist in a layout exactly once.
- Widget types in layouts must match what code expects (`MaterialButton`,
  `MaterialSwitch`, `TextInputEditText`, ...). This repository's harness UI was audited
  2026-11-27: 24/24 ids resolve, widget types match, see `changes.md` "button audit".
- Layout XML must be well-formed (ElementTree parse of all 244 XML files = 0 errors).

## Git hygiene

- `local.properties` is git-ignored and must never be committed.
- Never commit `/tmp` artifacts, `R.kt` stubs, `sdk.dir`, keystores or tokens.
- Branch is fixed: `arena/64ecbac3-android-code-studio2-0` — commit/push only there;
  PRs open from it (currently PR #1, open).
