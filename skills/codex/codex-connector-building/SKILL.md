---
name: codex-connector-building
description: Build a new OpenMetadata ingestion connector using the repository's schema-first architecture, appropriate source patterns, registration points, and focused verification.
---

# Codex Connector Building

Use this skill when adding or scaffolding a new ingestion connector. Begin with `codex-connector-standards` and inspect a comparable connector in the same service type before writing code.

1. Confirm the source type, connection model, supported capabilities, authentication, pagination, and SSL requirements. Use `metadata scaffold-connector` when it fits; otherwise add only the files the selected reference pattern requires.
2. Work schema-first. Update the connection/service schemas and registration, then regenerate models. Never hand-edit generated output.
3. Implement a connection client with clear errors, secret-safe logging, SSL verification, pagination, and bounded memory. Avoid wildcard lineage and unbounded caches.
4. Register the connector where the schema, Python distribution, UI service selector, icon, documentation, and example workflow require it. Follow the current files used by the comparable connector rather than a stale fixed list.
5. Add behaviour-focused unit tests and applicable connection/integration tests. Run the focused formatter, generator, and tests; use the connector analyzer before handoff.

Starting or rebuilding the full Docker stack is appropriate only when the user asks to test the connector in the UI or when the changed layer requires it. Do not create a PR or publish externally unless explicitly requested.
