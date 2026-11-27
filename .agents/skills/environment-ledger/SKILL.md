---
name: environment-ledger
description: Binding rules for appending dated, evidence-backed measurements to Environment.md without overwriting prior agent sections — network reachability, toolchain versions, receipts, and superseded-claims bookkeeping. Use whenever the environment changes or a new receipt is measured.
---

# Environment.md ledger rules

## Purpose

`Environment.md` is the durable, append-only ledger of what is actually reachable and
verified from this sandbox. It exists because agents repeatedly guessed the environment
wrong; measurements, not assumptions, belong here.

## Rules (binding)

1. **Append, never rewrite.** Add new sections at the end with a dated header:
   `## YYYY-MM-DD — <short title>`. Previous sections (including earlier agents') remain
   verbatim.
2. **Every entry is evidence-backed.** Command + exact output snippet or exit code. No
   "should", no "probably works".
3. **Mark superseded claims explicitly.** If a new measurement contradicts an earlier entry,
   say so and point at the new entry (keep the old text intact), e.g.:
   `NOTE: the "delegated to GitHub Actions" note in 2026-11-27 (late 2) is SUPERSEDED —
   Actions is unregistered/disabled for this fork.`
4. **Separate measurement from recommendation.** Facts (hosts reachable, versions) go in the
   ledger; instructions/recipes stay in the recipe sections; decisions stay with the maintainer.
5. **Scope honesty.** State what was validated and what was not. A stub-compile is never
   recorded as "builds on device".
6. **No new hosts without probing.** A host may be listed as reachable/blocked only after a
   real attempt was made and the result recorded.
7. **Keep changes.md consistent.** If `changes.md` claims a check that `Environment.md`
   contradicts, fix `changes.md` in the same edit and say so in its entry.

## Entry template

```markdown
## YYYY-MM-DD — <title>

- Context: <branch/PR/commit>
- Measurements: <commands + outputs>
- Consequences: <what may / may not be claimed>
- Supersedes: <older entry, or "none">
```
