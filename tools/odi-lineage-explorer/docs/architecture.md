# ODI Lineage Explorer architecture

Status: MVP decision record. This document describes the explorer that precedes any OpenMetadata
connector or metadata export contract.

## Product boundary

ODI Lineage Explorer is an independently buildable and deployable product located under
`tools/odi-lineage-explorer` so that it can share this repository's skills, documentation, and
development context. Its location in the monorepo does not make it an OpenMetadata runtime module.

```text
React frontend
    -> internal REST API
Java 21 backend
    -> user-provided, version-specific ODI SDK libraries
    -> ODI Master and Work repositories (read only)
```

The implemented and tested adapter targets ODI 14.1.2. The provider boundary may host an ODI 12c
adapter later, but no cross-version compatibility is assumed or claimed without version-specific
libraries and integration tests.

- The frontend is a React application for connecting to ODI, browsing Load Plans, selecting a
  Context and mappings, and inspecting lineage graphs.
- The Java backend owns ODI SDK access and session lifecycle. It must not import or require the
  OpenMetadata server or ingestion runtime.
- The explorer has its own build and deployment lifecycle. It must be possible to install and run
  it without installing or starting OpenMetadata.
- Oracle ODI SDK JARs remain user-provided and outside Git. They are mounted or referenced at build
  and runtime and are never packaged into a distributable artifact.
- `tools/odi-extractor` remains an SDK feasibility spike. Proven behavior may move into a dedicated
  explorer adapter later; the explorer must not depend on the spike as a running service.

The REST payloads exchanged between the backend and browser are internal view DTOs. Although they
are encoded as JSON, they are **not** the future export format and carry no compatibility promise for
OpenMetadata ingestion.

## MVP workflow

1. The user enters the repository JDBC details, Work Repository, ODI username, and passwords.
2. The backend creates a short-lived in-memory connection session and returns an opaque session ID.
3. The dashboard reads and displays the available Load Plans and their step trees.
4. The user opens a Load Plan, selects a Context, and selects all or individual Mapping occurrences.
5. The user opens a Mapping to inspect real source and target datastores, table-level paths, and
   column-level dependencies. Transformation components remain internal to lineage traversal.
6. Closing or expiring the session destroys the ODI SDK instance and removes the credentials from
   application memory.

Connections are never saved in an application database, file, browser storage, or reusable profile.
Passwords must not appear in URLs, DTO responses, exceptions, telemetry, or logs. Every new session
requires the user to provide the credentials again.

ODI 14.1.2 `MasterRepositoryDbInfo` retains the supplied repository-password array in the live
`OdiInstanceConfig` for pooled connections. Request-scoped copies are erased after setup, but the one
session-owned array must remain valid until `OdiInstance.close()` and is erased during provider cleanup.
Timed-out SDK workers and their buffers are globally bounded; buffers must not be erased concurrently
while an ODI connection attempt can still consume them.

## Naming and datastore identity

ODI exposes several names for one datastore occurrence. They must remain separate throughout the
backend and UI:

| Value | Meaning | MVP use |
|---|---|---|
| component alias | Mapping-local label, which may be changed and may differ for two uses of the same datastore | Secondary occurrence metadata shown beside the physical name |
| datastore name | Name of the `OdiDataStore` design object in the ODI Model | Design-time metadata shown to the user |
| resource name | Actual database object name configured on the datastore | Primary table label after topology resolution |

The component alias must never replace the datastore or resource name. Two components with aliases
such as `CUSTOMER_SOURCE` and `CUSTOMER_LOOKUP` may be separate graph occurrences backed by the same
datastore and physical table. Conversely, equal aliases in different mappings do not establish object
identity.

The explorer preserves all three values and their provenance. Internal graph IDs use the qualified
component path, including every concrete Reusable Mapping instance. This prevents two uses of one
Reusable Mapping definition from merging their internal datastore occurrences. These transport IDs
are not permanent canonical table identifiers or OpenMetadata FQNs.

## Mapping and column lineage projection

The browser payload contains only bound `DatastoreComponent` endpoints for which ODI reports a source
or target role. `Filter`, `Join`, `Lookup`, `Expression`, `Aggregate`, `Distinct`, `Set`, signature,
and Reusable Mapping components are traversal nodes only. They are never exposed as tables, included
in the accessible lineage table, or eligible for a future entity export.

```text
bound source datastore column
    -> attribute expressions and cross-references
    -> projector/selector/composite components
    -> Reusable Mapping input/output signatures
    -> active bound target datastore column
```

Column dependencies come from ODI SDK object references, not expression-text parsing. The reader
walks every target attribute expression and `MapExpressionXRef`, follows composite child attributes,
and keeps only upstream attributes bound to columns of actual source datastores. A target column can
therefore have zero, one, or many source columns, and a source column can affect multiple targets.
Filter and join predicates may influence row selection but do not become value-level column edges
unless a target attribute expression itself references their attributes.

`MapExpressionXRef.isValid()` is not a lineage eligibility flag. ODI 14.1.2 persists it separately
and can leave it false while `getReferencedAttribute()` resolves a valid `MapAttribute`, including
through `MapReference`. The reader therefore follows every non-null referenced attribute, matching
the SDK's own `MapAttribute.getReferences(..., true)` behavior. A null referenced attribute can be a
variable, sequence, function, or another non-column reference and is ignored rather than reported as
incomplete lineage.

Reusable Mappings require an instance-aware scope. Entering an output signature pushes the concrete
Reusable Mapping component; crossing an input signature pops it and resolves the caller attribute.
The scope is part of component and column transport IDs, so nested and repeated instances remain
separate. Traversal has bounded depth, state, edge, and warning counts. SDK parse/read failures,
broken reusable-signature bridges, and exhausted safety limits produce an
explicit incomplete-lineage warning; the reader never guesses a column from an alias or expression
string.

Table arrows are collapsed source-to-target paths through hidden transformations. Column arrows use
the resolved bound-column dependencies. The UI keeps sources on the left and targets on the right,
allows column lists to be expanded, highlights both upstream and downstream relationships on click,
and provides a text table as the accessible source of truth. Large responses do not render every
column edge twice: above the eager SVG threshold the graph draws at most 1,000 paths for the selected
column, while the accessible table replaces pages of at most 250 rows instead of growing the DOM.

## Context and topology resolution

A Model references a Logical Schema. A Logical Schema is not sufficient to identify a database
object because its physical target varies by ODI Context.

```text
selected Context
    + Model's Logical Schema
    -> Physical Schema mapping
    -> Data Server
    -> catalog and schema
    + datastore resource name
    -> physical object preview
```

The backend must return each intermediate value rather than flattening the resolution into one label:

- selected Context;
- Model and Logical Schema;
- resolved Physical Schema;
- Data Server;
- catalog and schema names;
- datastore name and resource name.

The user-selected Context controls the preview. If a Load Plan step declares its own Context, that
declared value is retained separately and a difference is visible in the UI; it must not be silently
discarded or blended with the selected preview Context.

Missing, ambiguous, or inaccessible Logical-to-Physical mappings produce an explicit unresolved
result. The backend must not guess a schema, fall back to an arbitrary Context, or normalize names in a
way that loses quoting or case information. Final normalization rules are deferred until representative
repository data has been observed.

## Load Plan to Mapping resolution

The Load Plan hierarchy is meaningful and must be preserved, including serial, parallel, case, and
run-scenario steps. A flat list may be offered as a UI projection, but it must retain the path to each
original step.

Resolution follows this chain:

```text
Load Plan step tree
    -> Run Scenario step
    -> Scenario Tag (scenario name and exact version)
    -> Scenario
    -> originating Mapping, or Package containing Mapping steps
```

- For a Mapping scenario, resolve the originating Mapping through `sourceMappingId`.
- For a Package scenario, inspect the originating Package and include its `StepMapping` references.
  Other Package step types remain visible as unsupported rather than being treated as mappings.
- Procedures and variables are outside the MVP lineage scope. They may be listed as unsupported but
  are not selectable for Mapping lineage.
- Repeated execution of the same Mapping at different Load Plan paths remains visible as distinct
  occurrences. The UI may group them for convenience but must not erase execution context.
- Resolve the exact Scenario version from the Scenario Tag. Never silently substitute the latest
  Scenario.

A Scenario can outlive or diverge from its design-time Mapping or Package. The adapter must expose
`resolved`, `stale`, `unresolved`, and `unsupported` outcomes with a reason. A stale or unresolved
reference is shown to the user and excluded from claims of complete lineage; it is not repaired by
guessing another object.

## Read-only SDK boundary

Read-only behavior is a security boundary, not a UI convention.

### Instance and session lifecycle

- Construct the SDK instance with `OdiInstance.createInstance(config)`, using the overload without an
  application name.
- Configure finite `PoolingAttributes` for every repository connection pool. Minimum, maximum, and
  idle limits must be explicit where ODI 14.1.2 exposes them; adapter operation timeouts bound caller
  wait. An unbounded pool or executor is forbidden.
- Give every connection session its own dedicated single-thread executor with a bounded queue. All ODI
  SDK operations and traversal of SDK-managed objects for that session run on that executor. SDK entity
  managers and attached domain objects must not be shared across threads or sessions.
- Enforce a global upper bound on live SDK executors. Expiry, logout, or failure requests SDK-thread
  cleanup. If an SDK operation ignores interruption, its executor and session buffer keep consuming a
  global permit until the task exits and late cleanup can close the `OdiInstance`.

### Entity-manager usage

Obtain `getTransactionalEntityManager()` while no SDK transaction is active and treat it strictly as a
query-only entity manager. Allowed operations are finder and read traversal operations needed to load
Contexts, topology objects, Load Plans, Scenarios, Packages, Mappings, components, attributes, and
expressions.

The adapter must not expose a generic entity manager to web resources. The following are forbidden in
production code:

- beginning, committing, or rolling back an ODI transaction;
- `persist`, `merge`, `remove`, `flush`, bulk update, or delete operations;
- setters or domain operations that mutate attached ODI entities;
- Scenario generation, Load Plan generation, imports, topology edits, security administration, or
  execution APIs;
- direct SQL DML or stored procedures that can mutate either repository.

Code review and automated dependency scans should reject these calls in the SDK adapter. A read-only
method name alone is not considered sufficient proof.

### Database-enforced read-only boundary

Query-only application code does not replace database authorization. The runtime must therefore use
both:

1. an ODI identity restricted to repository browsing; and
2. a dedicated database account that can read the required Master and Work Repository objects but has
   no `INSERT`, `UPDATE`, `DELETE`, DDL, or write-capable procedure privileges.

Database auditing must record attempted and successful DML for that account. Before production
acceptance, test login, Load Plan discovery, Context resolution, Mapping traversal, repeated sessions,
and logout against a disposable repository and prove from database audit records that no DML succeeded
or was attempted. Include a negative write test proving the database account rejects DML.

If any login or SDK operation requires a repository write, the connection must fail closed and the
administrative action must be completed outside this application. The explorer must not receive DML
privileges to make authentication convenient.

## Non-goals for this MVP

- Exporting JSON, NDJSON, XML, or any other interchange artifact.
- Defining the future export schema.
- Integrating with, embedding in, or writing entities to OpenMetadata.
- Choosing stable external IDs, OpenMetadata FQNs, deduplication keys, or repeated-import update
  semantics.
- Executing Scenarios, Mappings, Procedures, Packages, or Load Plans.
- Creating or modifying ODI repository objects.
- Deriving lineage from Procedures or arbitrary SQL.
- Publishing transformation components as database entities.
- Exporting expression text or creating synthetic tables for Mapping components.
- Persisting connection profiles, repository credentials, or explorer sessions across restarts.

The internal REST JSON exists only to render the current UI. It must not be reused as an export format
by accident.

## Production acceptance criteria for the real ODI SDK adapter

The adapter is implemented and enabled for local ODI 14c lab validation. It may be called
production-ready only after all of the following are available:

- The selected version's client JARs are provided outside Git and its Java 21 classpath probe passes.
- A disposable or sanitized Master and Work Repository is reachable with the dedicated no-DML database
  account and read-only ODI identity.
- Database DML auditing is enabled for that account and produces evidence usable by automated tests.
- Deterministic fixtures cover aliases, datastore/resource names, at least two Contexts mapping one
  Logical Schema to different Physical Schemas, nested Load Plan step types, exact Scenario versions,
  direct Mapping scenarios, Package `StepMapping` references, repeated Mapping occurrences, and
  stale/unresolved references.
- Column-lineage fixtures cover multiple sources, multiple targets, multiple target expressions,
  aggregate/group-by, Dataset composite attributes, nested Reusable Mappings, and two instances of
  the same Reusable Mapping without cross-instance edges.
- SDK session limits, bounded `PoolingAttributes`, the bounded single-thread executor, expiry, cleanup,
  and secret redaction have focused tests.
- A read-only integration test completes the representative browsing workflow and confirms through
  database audit records that repository state did not change.

## Deferred contract decision

Only after the explorer displays real repository metadata correctly will the project design an export
contract and the OpenMetadata adapter. That later decision must use observed values for aliases,
resource names, Context resolution, duplicate Mapping occurrences, stale Scenarios, and multi-source /
multi-target lineage.

Stable naming, FQN construction, and idempotent repeated imports are deliberately unresolved here. They
must be designed together with the future OpenMetadata ingestion contract so that importing the same
logical object updates it instead of creating duplicates.
