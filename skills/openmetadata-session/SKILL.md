---
name: openmetadata-session
description: Load and preserve working context for OpenMetadata sessions. Use at the first substantive turn, when resuming work, or when the user explicitly pauses, ends, or asks to save a session handoff. Provides a read-only start health check and a minimal finish update; do not use for ordinary mid-session summaries.
---

# OpenMetadata Session

Keep work resumable without expanding the task or duplicating project documentation.

## Select one mode

- Start: use once before substantive work in a new or resumed session. This is the default when no
  mode is supplied and no session context has been loaded. Read references/start.md and follow it.
- Finish: use only when the user explicitly pauses or ends work, asks to save context, or invokes
  finish. Read references/finish.md and follow it.

Do not read both references for one invocation. Do not run Finish after every response. Closing a
client cannot reliably trigger semantic work, so an explicit end-of-session message is the trigger.

## Shared constraints

- CLAUDE.md and matching path rules remain authoritative.
- Preserve the user-owned working tree. Never clean, reset, stash, normalize, commit, or push it.
- Start is strictly read-only: no installs, fetches, service startup, generation, or repairs.
- Finish may edit only the active docs/codex-work handoff and its README entry when a new topic is
  genuinely needed.
- Run safe local checks autonomously. Platform permissions still apply; a denied check is
  NIESPRAWDZONE, never OK.
- Never expose secrets, environment values, headers, credentials, full MCP configuration, private
  keys, production data, or licensed vendor artifacts.

## Response contract

Respond in the user's language. Put status first, use at most five short lines, make verified progress
visible, and finish with exactly one concrete next action when work remains.
