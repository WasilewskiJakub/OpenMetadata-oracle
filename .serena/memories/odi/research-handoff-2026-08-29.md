# ODI connector research hand-off — 2026-08-29

> Superseded for current decisions by `docs/codex-work/odi-14c-context.md` (2026-08-30).
> Keep this memory only as historical research context; do not treat its decision list as current.

Canonical tracked notes: `docs/odi-connector-readiness-analysis.md`, section `ODI parser and lineage tooling research — 2026-08-29`.

Key conclusions:
- No credible ready-made ODI-specific skill, plugin, or MCP was found.
- Prefer the official ODI Java SDK for design-time mappings, Load Plans, scenarios, and precise table/column lineage.
- Use a version-specific Java extractor subprocess producing stable versioned JSON/NDJSON for the Python OpenMetadata pipeline connector.
- ODI 12.2.1.4 and ODI 14.1.2 require separate runtime consideration; 12c is Java 8-era, 14.1.2 is Java 17/21-era.
- Do not distribute Oracle ODI Enterprise client JARs; accept a user-provided ODI home/client-library path.
- Runtime SOAP services are monitoring/control supplements, not a design-time mapping source.
- Direct SNP_* SQL is a versioned, read-only fallback only. Smart Export XML is useful for fixtures/offline mode but Load Plan exports omit referenced scenarios unless exported separately.
- Model Mapping and Load Plan as OpenMetadata Pipelines; components/steps as Tasks with downstreamTasks; table-to-table lineage references the Mapping pipeline; SDK bound columns/attribute references drive ColumnsLineage.
- Reuse NiFi task-graph, Airflow pipeline-lineage, and OpenLineage dataset-resolution patterns in the repo.
- External shortlist, not installed: oracle/skills@db; Oracle SQLcl MCP for sanitized read-only repository exploration; openai/skills@security-threat-model; Astronomer creating-openlineage-extractors only if runtime event emission is in scope.
- Highest-value future addition is a project-local `odi-connector-development` skill after version/access decisions and representative fixtures exist.

Decisions for next session:
1. ODI versions: 12.2.1.4, 14.1.2, or both.
2. Available access: SDK JARs, read-only repository, Smart Export, runtime SOAP.
3. Design repository, execution repository, or both.
4. ODI Context and datastore-to-OpenMetadata service mapping.
5. Static connector only or runtime OpenLineage emission too.
