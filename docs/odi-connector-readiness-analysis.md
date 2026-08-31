# OpenMetadata ODI connector readiness analysis

Status date: 2026-08-28

> Environment update (2026-08-31): the `/mnt/d` checkout and performance measurements below are
> historical evidence. The workspace bootstrap branch moves the durable skills, MCP configuration,
> Serena project settings, and handoff into Git before a root-owned clone on native WSL ext4.

This document preserves the repository analysis and working decisions made before implementation of an Oracle Data Integrator (ODI) connector. It is intended to make later Codex or Claude sessions reproducible without repeating the initial repository audit.

## Repository baseline

- Fork remote: `origin` -> `git@github.com:WasilewskiJakub/OpenMetadata-oracle.git`.
- Official remote: `upstream` -> `https://github.com/open-metadata/OpenMetadata.git`.
- `main`, `origin/main`, and `upstream/main` were synchronized at commit `f5d911d164`.
- Baseline commit: `fix(ci): skip Python checkstyle for 1.13 PRs (#32245)`, dated 2026-08-28.
- The fork was advanced by 679 official upstream commits from its previous `6e5bc803a8` baseline and pushed to `origin/main`.
- The working tree was clean immediately after synchronization.

## Local checkout caveat

The repository contains important symbolic links, but this checkout currently has:

```text
core.symlinks=false
core.autocrlf=true
```

Consequences observed in this WSL checkout:

- `AGENTS.md` is a regular file containing the text `CLAUDE.md`, rather than a symlink.
- Entries in `.claude/skills/` and `.agents/skills/` are regular text files containing target paths, rather than usable skill-directory symlinks.
- Shell scripts are checked out with CRLF line endings.

This does not change the Git tree: Git records these paths as mode `120000`. It does mean that local agent discovery and direct Linux execution of some scripts are impaired. No mass checkout or line-ending conversion was performed. Any repair should preserve user changes and be treated as an explicit workspace setup operation.

## Agent customization inventory

### Repository instructions

- Root `AGENTS.md` points to `CLAUDE.md` in the Git tree.
- `CLAUDE.md` is the authoritative repository development guidance for this checkout.
- `.claude/rules/` contains path-specific rules, including Python ingestion and schema-first guidance. These are Claude-native and are not automatically equivalent to Codex project rules.

### OpenMetadata skills plugin

The central `skills/` directory identifies itself as OpenMetadata Skills Plugin v2.0.0. It contains 23 `SKILL.md` workflows:

- `code-review`
- `connector-audit`
- `connector-building` (manifest name: `scaffold-connector`)
- `connector-review`
- `connector-standards`
- `dev-setup`
- `java-checkstyle`
- `openmetadata-workflow`
- `planning`
- `playwright-validation`
- `playwright` (manifest name: `playwright-test`)
- `pr-checklist`
- `systematic-debugging`
- `tdd`
- `test-enforcement`
- `test-locally`
- `ui-checkstyle`
- `ui-core-components`
- `verification`
- `writing-playwright-tests` redirect
- vendored Vercel `composition-patterns`
- vendored Vercel `react-best-practices`
- vendored Vercel `web-design-guidelines`

The plugin currently has a Claude manifest at `skills/.claude-plugin/plugin.json` and Claude hooks at `skills/hooks/hooks.json`. It does not have a native Codex universal plugin manifest at `skills/.codex-plugin/plugin.json`.

Claude hooks currently provide useful enforcement for:

- automatically loading `openmetadata-workflow` at session start;
- blocking `--no-verify`;
- reminding about Java formatting;
- reminding about schema regeneration;
- blocking direct edits to generated sources and generated docs;
- guarding workflow edits;
- running UI lint after relevant writes.

These protections are not all active in Codex merely because their Claude configuration exists. A later Codex adaptation should port only the useful repository-specific behavior and preserve Codex approval boundaries for external writes.

### Skill discovery mismatch

`.claude/skills/` exposes almost all repository skills. `.agents/skills/`, which is the native Codex repository discovery location, exposes only six:

- `composition-patterns`
- `dev-setup`
- `java-checkstyle`
- `react-best-practices`
- `ui-checkstyle`
- `web-design-guidelines`

It currently omits the connector, workflow, planning, testing, verification, and review skills most relevant to ODI work. In addition, the broken local symlink materialization means even the six entries are not usable as intended in this checkout. The active Codex session did not discover the repository connector skills, confirming the practical impact.

### Skill quality findings

- `skills/connector-audit/SKILL.md` formerly pointed at a nonexistent standards location under `connector-review`; it now points to `skills/standards/source_types/`.
- Several connector skills use `${CLAUDE_SKILL_DIR}/standards/...` while standards are stored as a sibling of the skill directories. This path behavior needs validation before porting.
- Several skills contain Claude-specific variables, tool names, slash commands, and subagent semantics. They need a compatibility pass rather than blind copying.
- `skills/standards/main.md` has a shorter, older connector registration checklist than `skills/standards/registration.md`.
- `registration.md` describes older Python formatting tools, while current root guidance uses Ruff. Root `CLAUDE.md` wins.
- `standards/code_style.md` hardcodes a copyright year. Current root guidance says to copy the appropriate sibling/module header instead of assuming a year.
- `skills/vendor/` is vendored material and should not be edited for repository-specific behavior.

### MCP distinction and Serena development tooling

The repository's `openmetadata-mcp/` module and MCP service connector schemas are OpenMetadata product/runtime functionality. They are not Codex development-tool configuration.

The initial audit found no repository-local Codex MCP setup. Serena was subsequently installed and configured as a development MCP because its symbol-aware navigation, reference lookup, diagnostics, and refactoring tools complement the repository skills. Repository knowledge and development procedures remain in instructions and skills; Serena supplies code intelligence rather than replacing them.

#### Installed state

- Serena `1.7.0` is installed globally for the WSL `root` user with `uv tool install -p 3.13 serena-agent`.
- The executable is `/root/.local/bin/serena`; Serena's global state is under `/root/.serena/`.
- `/root/.serena/serena_config.yml` registers exactly one project: `/mnt/d/NeuralForge/OpenMetadata-oracle`.
- No Serena entry was added to `/root/.codex/config.toml`.
- The only Codex MCP entry is `[mcp_servers.serena]` in this repository's `.codex/config.toml`. This uses the officially supported project-scoped MCP configuration mechanism documented at <https://learn.chatgpt.com/docs/extend/mcp?surface=cli>.
- `codex mcp list` reports Serena as enabled when run in this repository and reports no configured MCP servers when run from `/tmp`.
- The MCP command uses Serena's `codex` context, activates this repository by absolute path, disables the web dashboard/browser launch, does not make Serena a required Codex startup dependency, and prompts for tools classified as writes.

Serena's project configuration is `.serena/project.yml`. The repository already ignores `.serena/`, so its caches, memories, logs, and health-check results do not appear in Git status. The project configuration currently:

- enables the Python and TypeScript language servers;
- limits LSP workspaces to `ingestion/`, the main UI package, and the UI core-components package;
- preserves this checkout's CRLF line endings;
- honors `.gitignore` and explicitly excludes `.git`, `env`, `target`, `node_modules`, and `.yarn` trees;
- reminds the agent to follow `CLAUDE.md`, preserve schema-first generation, and prefer symbolic tools for code navigation and refactoring.

Java was deliberately removed from automatic Serena startup. Eclipse JDTLS successfully began importing the Maven reactor, but its first initialization downloaded the large dependency graph and remained in startup for several minutes. Add Java back only for a focused backend task or after moving the checkout to faster storage; do not make it part of every ODI-oriented Codex startup by default.

#### Validation and WSL performance caveat

The final focused command completed successfully:

```bash
serena project health-check /mnt/d/NeuralForge/OpenMetadata-oracle
```

It validated symbol overview, symbol lookup, and reference lookup; the reference test found 24 references. Python used Pyright `1.1.403`, TypeScript used the bundled TypeScript `5.9.3`, and both language servers initialized correctly.

The checkout lives on the Windows-mounted `/mnt/d` filesystem. Measured during the successful health check:

- ignore-spec gathering: about 48 seconds;
- Python workspace analysis: about 57 seconds for 2,616 Python files;
- initial file-change baseline polling: about 2 minutes 17 seconds;
- total language-server initialization: about 4 minutes 3 seconds;
- a reference lookup's pre-query filesystem poll: about 2 minutes 21 seconds.

The symbolic operation itself completed quickly after polling. The bottleneck is repeated filesystem traversal across WSL/NTFS, not symbol resolution. Serena is functional in the current location, but serious daily use should move the checkout to the native WSL filesystem or narrow the Serena project further. A newly started Codex session is required to load the new MCP entry; the session that created the configuration cannot hot-load it.

## Harness result

The read-only repository harness command completed successfully:

```bash
python3 scripts/harness/check_harness.py
```

It returned exit code 0 with 30 warnings:

- 1 agent synchronization warning: `AGENTS.md` should be a symlink to `CLAUDE.md`;
- 22 skill symlink warnings caused by this checkout's symlink handling;
- 3 dead-reference warnings:
  - `ARCHITECTURE.md` references missing build output `openmetadata-spec/target/`;
  - the documentation index referenced a removed harness-audit directory;
  - `skills/connector-audit/SKILL.md` references the wrong standards path;
- 2 documentation size warnings;
- 2 stale generated-document warnings for `docs/generated/api-reference.md` and `docs/generated/entity-index.md`.

## Connector architecture relevant to ODI

No existing Oracle Data Integrator connector or ODI-specific implementation was found.

OpenMetadata ingestion connectors live under:

```text
ingestion/src/metadata/ingestion/source/{service_type}/{connector_name}/
```

They are loaded through `service_spec.py` and the `ServiceSpec` contract. Connector configuration is schema-first: JSON schemas generate Python, Java, TypeScript, and UI artifacts.

The existing Oracle connector under `source/database/oracle/` is a database metadata/query/lineage connector. ODI should be implemented as a new **pipeline service connector**, not added as a special case to the Oracle database connector.

Recommended entity mapping:

```text
ODI repository/API
  -> PipelineService (Oracle ODI)
  -> Pipeline (scenario, load plan, package, or mapping execution unit)
  -> Pipeline tasks and statuses
  -> lineage edges to existing OpenMetadata Table entities
```

The Oracle database connector can create or resolve database/table entities. The ODI connector should use configured lineage service names and precise fully qualified names to connect ODI mappings to those entities.

The base `PipelineServiceSource` contract requires implementations for:

- `get_pipelines_list`
- `get_pipeline_name`
- `yield_pipeline`
- `yield_pipeline_status`
- `yield_pipeline_lineage_details`

It already provides filtering, deletion handling, lineage enablement, service-name-aware FQN resolution, and bulk-lineage support.

Useful existing open-source pipeline references include:

- NiFi for graph-shaped flows and lineage;
- Fivetran and Airbyte for source/destination table lineage;
- OpenLineage for event-oriented lineage;
- Airflow and Prefect for tasks and execution status.

The pipeline service schema lists more service types than the open-source source tree implements. A schema enum is therefore not proof that reusable open-source connector code exists.

## Producer-provided assets to reuse

- `metadata scaffold-connector`
- `skills/connector-building/GUIDE.md`
- connector architecture decision tree and capability mapping
- `skills/connector-building/examples/pipeline-sdk.yaml`
- `connector-profile.schema.json`
- `skills/standards/`, especially pipeline, schema, registration, lineage precision, testing, pagination, and bounded-memory guidance
- pipeline unit tests under `ingestion/tests/unit/topology/pipeline/`
- current neighboring connectors for code, license header, test, and registration conventions

The detailed registration flow currently spans:

1. Pipeline service enum and connection schema `oneOf`.
2. Python package extras in `ingestion/setup.py`.
3. Example workflow YAML.
4. UI connection-schema switch.
5. Service icon.
6. Service icon utility registration.
7. Connector documentation.
8. Beta-service registration when applicable.

## ODI implementation principles

- Treat ODI as a pipeline/orchestration source.
- Prefer an officially supported ODI API/SDK where it exposes all required metadata. Otherwise, use a read-only connection to ODI master/work repositories with explicitly versioned queries.
- Separate discovery, entity mapping, status ingestion, and lineage extraction.
- Resolve lineage against configured OpenMetadata database services; never guess service names.
- Do not emit wildcard or ambiguous lineage. Skipping an uncertain edge is safer than writing a wrong edge.
- Keep memory bounded and paginate repository reads.
- Add focused unit fixtures before integration tests.
- Determine the target ODI version and available access method before finalizing the connection schema; this is the first genuinely product-dependent design decision.

## ODI parser and lineage tooling research — 2026-08-29

No credible, maintained skill, plugin, or MCP server dedicated specifically to Oracle Data Integrator
was found in the public skill catalog, Oracle's MCP catalog, or relevant open-source repositories. The
recommended approach is therefore to combine the repository's existing OpenMetadata connector skills,
official Oracle tooling, and a future project-local ODI skill rather than adopting a generic ETL or
lineage package.

### Preferred metadata access architecture

The official ODI Java SDK is the preferred source of design-time metadata:

- `OdiInstance` is the supported entry point to master/work repositories and exposes read-only entity
  management when no transaction is active.
- `IMappingFinder` can enumerate mappings and find mappings by source/target datastore or referenced
  object.
- `Mapping` and `IMapComponentOwner` expose the component graph, sources, targets, reusable mappings,
  connectors, and physical designs.
- `MapAttribute`, `MapExpression`, bound datastore/column objects, and upstream leaf attributes provide
  the information needed for table- and column-level lineage without guessing from generated SQL.
- `OdiLoadPlan`, its serial/parallel/case/run-scenario step classes, `OdiScenario`, and load-plan/session
  runtime entities provide the process hierarchy and execution status model.

Oracle's documented web-service surface is runtime-oriented: start, stop, restart, and status operations
for scenarios and Load Plans. It is useful for monitoring but is not a replacement for the SDK when
extracting the complete design-time mapping graph.

The SDK materialized client libraries (`oracle.odi.common.clientLib.jar`,
`oracle.odi.tp.clientLib.jar`, and `oracle.odi.sdk.clientLib.jar`) are supplied by an ODI Enterprise
installation. They should be provided by the user through an ODI installation/library path and must not
be copied into this repository or distributed in the OpenMetadata ingestion package without a separate
licensing decision.

The Java runtime must be isolated by ODI version. ODI 12.2.1.4 documentation uses the Java 8 generation,
whereas Fusion Middleware/ODI 14.1.2 uses Java 17/21. A version-specific Java extractor process that
streams a stable, versioned JSON or NDJSON intermediate representation to the Python connector avoids
coupling the Python ingestion runtime and this repository's Java 21 build to incompatible ODI SDK
versions.

Direct read-only SQL against `SNP_*` repository tables remains a fallback only. Any such implementation
must use explicit per-version query adapters and fixtures because repository-table access is more brittle
than the public SDK. Smart Export XML is useful for offline fixtures or an optional import mode, but a Load
Plan export does not automatically include its referenced scenarios, so it is not sufficient by itself.

Official references:

- <https://docs.oracle.com/en/middleware/fusion-middleware/data-integrator/12.2.1.4/odija/oracle/odi/core/OdiInstance.html>
- <https://docs.oracle.com/en/middleware/fusion-middleware/data-integrator/12.2.1.4/odija/oracle/odi/domain/mapping/finder/IMappingFinder.html>
- <https://docs.oracle.com/en/middleware/fusion-middleware/data-integrator/12.2.1.4/odija/oracle/odi/domain/mapping/Mapping.html>
- <https://docs.oracle.com/en/middleware/fusion-middleware/data-integrator/12.2.1.4/odija/oracle/odi/domain/mapping/MapAttribute.html>
- <https://docs.oracle.com/en/cloud/paas/data-integrator-cloud/using/using-load-plans.html>
- <https://docs.oracle.com/en/cloud/paas/data-integrator-cloud/user/running-integration-processes.html>
- <https://docs.oracle.com/en/middleware/fusion-middleware/data-integrator/14.1.2/odiun/overview-oracle-data-integrator.html>

### Proposed OpenMetadata representation

- ODI Mapping → `Pipeline`.
- Mapping components → `Task` objects; ODI connectors → `Task.downstreamTasks`.
- ODI Load Plan → a separate `Pipeline`; serial, parallel, case, exception, and run-scenario elements →
  flattened tasks with stable hierarchical identifiers and explicit downstream relationships.
- Scenario → the executable version referenced by a Load Plan task, retaining the link to the originating
  Mapping, Package, Procedure, or Variable where the SDK exposes it.
- ODI session and Load Plan run history → `PipelineStatus` and task statuses.
- Source datastore → target datastore → table lineage, annotated with the Mapping pipeline in
  `LineageDetails.pipeline`.
- Bound source/target columns and attribute-expression references → `ColumnsLineage`.
- ODI Context must be explicit connector configuration because logical schemas can resolve to different
  physical databases and schemas in different contexts.

Existing implementation patterns to reuse are NiFi for a graph of tasks and `downstreamTasks`, Airflow
for table-to-table lineage annotated with a pipeline, and the OpenLineage source for dataset resolution,
bounded caches, runtime status, and column-lineage emission.

### Tooling shortlist — nothing installed during research

1. Existing repository skills (`codex-connector-standards`, `codex-connector-building`, TDD, test
   enforcement, local testing, verification, and connector review) remain the mandatory workflow.
2. `oracle/skills@db` is the strongest external skill candidate. It is maintained by Oracle and covers
   JDBC, Python Oracle access, safe read-only database workflows, schema discovery, and SQLcl MCP.
3. Oracle SQLcl MCP Server is the strongest optional development MCP once a sanitized or development ODI
   repository is available. Use a dedicated least-privilege read-only account; do not connect it directly
   to production. It is for repository exploration and validation, not a connector runtime dependency.
4. A project-local `odi-connector-development` skill should be created after selecting the target ODI
   versions and obtaining representative fixtures. It should contain the SDK class map, version matrix,
   intermediate JSON contract, ODI→OpenMetadata mapping, context/FQN resolution rules, lineage precision
   rules, and fixture/golden-test workflow.
5. `openai/skills@security-threat-model` is useful before live database access or execution of the Java
   extractor.
6. `astronomer/agents@creating-openlineage-extractors` is relevant only if an ODI-side process will emit
   OpenLineage runtime events. It does not replace design-time SDK extraction.
7. `wshobson/agents@architecture-decision-records` is optional; the repository's existing planning and
   design-document workflow may already be sufficient.

Oracle SQLcl MCP is the supported database MCP option. The `oracle/mcp` Oracle Database MCP Toolkit is a
reference/proof-of-concept implementation and should not be selected as a production dependency. No
ODI-specific MCP server was found.

Generic XML parser, generic ETL, BigQuery lineage, DataHub lineage, and low-adoption Java/Maven skills were
rejected as either unrelated or weaker than the repository's existing standards. The ingestion package
already includes `lxml` and its own SQL/column-lineage machinery, so additional generic parser skills are
not justified.

### Decisions required before implementation

1. Supported ODI versions: 12.2.1.4, 14.1.2, or both.
2. Available access: Enterprise SDK libraries, read-only repository SQL, Smart Export files, and/or
   runtime SOAP services.
3. Repository topology: design work repository, execution repository, or both.
4. ODI Context selection and the mapping from ODI physical schemas/datastores to existing OpenMetadata
   database service names.
5. Whether runtime OpenLineage emission is in scope or the first version remains a scheduled static
   metadata connector.

## Codex context configuration finding

The model-context inspection was performed with Codex CLI `0.146.0`. By the Serena hand-off, `codex --version` reported `0.150.1`. The earlier CLI's bundled model catalog reported for `gpt-5.6-sol`:

```text
default context_window: 272000
max_context_window:     1000000
effective percentage:  95
```

The observed approximately 258k context is `272000 * 0.95`, which confirms that the default window is active.

The global `~/.codex/config.toml` already contained intended larger-window values, but they were placed after `[tui.model_availability_nux]`. TOML therefore interpreted them as fields inside that table instead of root model settings. A project-level `.codex/config.toml` with root-level keys is the appropriate repository-scoped fix. Configuration changes apply to newly started Codex sessions; they do not resize the already-running thread.

The repository-scoped configuration now selects `gpt-5.6-sol`, declares the `1000000` maximum window, triggers automatic compaction at `750000` tokens to retain working headroom, and enables Serena only for this repository.

## Docker/environment state

- Canonical checkout: `/root/workspaces/openmetadata-oracle` on native WSL ext4, owned by `root`.
- Ubuntu's registered WSL `DefaultUid` is 0, matching the Codex process and checkout owner.
- Docker Desktop's WSL integration is operational.
- The DevContainer runs as `root` against the root-owned bind mount and completed its full
  post-create workflow.
- Verified container toolchain: Java 21.0.12.1, Maven 3.9.9, Node 22.17.0, Yarn 1.22.22,
  Python 3.11.16, and ANTLR 4.9.2.
- Named volumes preserve root/UI node_modules, the DevContainer venv, and ingestion egg-info.

## Working tree at hand-off

The clean `codex/workspace-bootstrap` branch tracks `origin/codex/workspace-bootstrap`. Durable agent
configuration is committed under `.agents`, `.claude`, `.codex`, `.serena`, and `skills`; only Serena
cache and health-check logs remain ignored. The previous `/home/jakub` checkout is retained as backup.

## Recommended next step

Start a new Codex session from `/root/workspaces/openmetadata-oracle`, invoke `openmetadata-session`
in Start mode, then continue with the ODI certification-matrix decision recorded in the handoff.
