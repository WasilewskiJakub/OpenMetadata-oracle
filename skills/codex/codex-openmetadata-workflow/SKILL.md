---
name: codex-openmetadata-workflow
description: Route non-trivial OpenMetadata development work to the relevant local practices for planning, debugging, testing, UI, connectors, and final verification.
---

# Codex OpenMetadata Workflow

Use this skill for multi-file features, bugs with an unclear cause, connector work, or changes spanning Java, Python, schemas, and the UI.

Read `CLAUDE.md` first; it is the repository authority. Then select only the guidance relevant to the task:

- Feature or refactor: use `planning` when the design has meaningful alternatives; verify affected layers before editing.
- Failing build, test, or runtime behaviour with an unclear cause: use `systematic-debugging` before attempting fixes.
- Implementation: use `tdd` where a small behaviour-first test is practical.
- Java: run `java-checkstyle` after editing Java. UI: read the UI handbook, then use `ui-core-components`, `playwright-test`, and `ui-checkstyle` only when their triggers match.
- Connector creation or review: use `codex-connector-standards` and the corresponding connector skill.
- Before completion: use `test-enforcement` when its coverage checks apply, then `verification` with commands proportional to the change.

Do not require a heavyweight plan, E2E suite, or full Docker build for a narrow, low-risk change. Do not publish to GitHub, create a PR, or change external services unless the user explicitly asks.
