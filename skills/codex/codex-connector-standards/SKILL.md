---
name: codex-connector-standards
description: Load only the OpenMetadata connector standards relevant to building, debugging, or reviewing an ingestion connector.
---

# Codex Connector Standards

Start with `../../standards/main.md`, then load the smallest applicable set:

- Always for connector code: `patterns.md`, `code_style.md`, `testing.md`, `performance.md`, and `memory.md`.
- Schema or service registration: `schema.md` and `registration.md`.
- Connection or authentication: `connection.md`; add `sql.md` for SQLAlchemy databases.
- Lineage: `lineage.md`.
- Service-specific decisions: the matching file under `../../standards/source_types/`.

Preserve the repository's schema-first flow: edit schemas rather than generated models, then regenerate required Python, Java, and TypeScript outputs. Treat pagination, bounded memory/caches, SSL verification, secret handling, and precise lineage as production correctness concerns. Cite the applicable standard in any review finding; do not load all standards merely to answer a focused question.
