# Start mode

Load enough context to continue safely, then return a compact status. Do not modify files or external
state.

## Procedure

1. Run from the repository root:

       bash skills/openmetadata-session/scripts/session-health.sh

   Continue after warning status. Stop substantive work only for a blocker.

2. Load context selectively:

   - Treat CLAUDE.md as loaded when the harness already supplied it; otherwise read it.
   - Read docs/codex-work/README.md and list its topic files.
   - Select the active topic from the request, branch, changed paths, and headings, then read that
     topic file completely. If none matches, report that neutrally.
   - Use docs/index.md to locate only relevant documentation.
   - Read only relevant sections of ARCHITECTURE.md and DEVELOPER.md.
   - Inspect git status and a diff summary without attributing pre-existing changes to this session.

3. Check dependencies once per session:

       make dev_check

   This target is diagnostic and read-only. Do not run setup, generation, full builds, test suites,
   Docker startup, git fetch, or dependency installation.

4. Check task-relevant skill entrypoints and symlink targets directly. When the skill validator is
   available, validate only those skills. Do not run make harness-check or
   scripts/harness/check_harness.py during Start because both regenerate docs/generated files.
   Mark the full harness check NIESPRAWDZONE and read only task-relevant SKILL.md files.

5. Check MCP read-only:

   - Enumerate configured project MCP names and visible mcp__server__ tool prefixes without printing
     commands, arguments, environment values, headers, or full config.
   - Probe only a known read-only endpoint. For Serena, use get_current_config when exposed.
   - Never activate projects, restart servers, install components, or call write tools.
   - Classify each project MCP as HEALTHY, AVAILABLE / UNPROBED, CONFIGURED / UNAVAILABLE, DISABLED,
     or NOT CONFIGURED. No published resources does not prove a server is unhealthy.
   - A required MCP must be HEALTHY for the overall result to be OK. Report app connectors separately.

## Status

- OK: repository/EOL checks pass, required context is readable, relevant dependencies work, skill
  integrity has no blocker, and required MCPs are healthy.
- Warning: work can proceed but an optional check failed or was not inspectable.
- Blocked: wrong/unreadable repository or a requirement for the current task is unavailable.

A dirty working tree is context, not a warning.

Use at most five lines:

    ✅ Jest OK — kontekst gotowy.
    Temat: <topic and current state>.
    Repo: <branch@head>; <change count>; Linux path/EOL.
    Gotowe: <toolchain>; skille <status>; MCP <status>.
    Teraz: <one immediate action>.

For non-OK results use ⚠️ Można pracować — N uwag or ⛔ Blokada — reason. Name every
NIESPRAWDZONE check.
