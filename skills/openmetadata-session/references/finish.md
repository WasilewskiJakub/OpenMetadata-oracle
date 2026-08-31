# Finish mode

Preserve only durable facts needed to resume the active topic. Do not create a transcript or rewrite
project documentation.

## Procedure

1. Establish evidence from the conversation, the Start snapshot when available, current git status,
   a scoped diff/stat, and commands actually run. Recheck status before editing so concurrent or
   pre-existing changes are not attributed to this session.

2. Choose the handoff:

   - Prefer the topic file loaded in Start.
   - Otherwise match the request, branch, and changed paths to one existing topic file.
   - Create docs/codex-work/topic-context.md only for durable unfinished context with no suitable
     file, then add one entry to docs/codex-work/README.md.
   - If several files are equally plausible, do not write any of them.
   - If nothing durable changed, do not touch the notes.

3. Apply the smallest semantic patch:

   - update Last updated;
   - correct Current state only where stale;
   - add verified decisions or completed work to an existing journal or decision section;
   - record focused verification commands and their real results;
   - replace Next session or Next step with exactly one nearest concrete action.

   Do not duplicate facts, add a generic daily diary, copy the full diff, reformat unrelated sections,
   or move stable architecture into working notes. Never record secrets or licensed artifacts.

4. Review the documentation diff and run:

       git diff --check -- docs/codex-work

   Finish never commits, pushes, opens a PR, repairs code, or claims an unrun test passed. If required
   verification is missing, make that verification the one next step.

Use at most five lines:

    ✅ Sesja zamknięta — hand-off aktualny.
    Zrobione: <one verified outcome>.
    Sprawdzone: <command and result, or not run>.
    Notatka: <path, or no durable update>.
    Dalej: <exactly one nearest action>.
