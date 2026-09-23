# ODI Lineage Export v1

`odi-lineage-export-v1.schema.json` defines the stable exchange contract emitted by ODI Lineage
Explorer for future metadata consumers. It is a snapshot of the mappings selected from one Load Plan
under one explicitly selected ODI Context. No OpenMetadata importer is currently present.

This contract is deliberately separate from the internal REST view DTOs. It contains physical
database endpoints and column dependencies, but no transformation nodes, connection details,
credentials, raw expressions, or literal values.

## Contract rules

- `schemaVersion` is exactly `1.0`.
- Every declared property is required. A vendor field that ODI cannot supply is present as `null`;
  arrays are present even when empty.
- `source.repository` is the safe repository descriptor
  `{name, masterRepository, workRepository}`. These are repository names, not JDBC details or
  repository passwords.
- `loadPlan.contextCode` is the Context selected for this export. Every `mappings[].contextCode` must
  equal it.
- `mappingOccurrences` contains only occurrences selected for export. Every `mappingId` must resolve
  to exactly one item in `mappings`.
- IDs inside a Mapping are references within this document. They are not OpenMetadata UUIDs or FQNs.
- Collections must be emitted in deterministic order so exporting unchanged ODI metadata produces a
  reviewable diff. Semantic keys such as occurrence ID, Mapping ID, endpoint ID, and target column
  must be unique in their containing scope.
- The artifact is pretty-printed JSON and ends with exactly one newline. Formatting is not part of
  JSON Schema validation, but keeping it deterministic makes artifacts easy to review and compare.
- There is intentionally no `generatedAt` field, so unchanged repository metadata can produce an
  identical artifact.

The JSON Schema enforces object shapes, enumerations, nullability, and the cardinality rules for
column derivations. Cross-reference rules such as endpoint existence, endpoint role, and Context
equality must be checked by the exporter and any consumer.

One request accepts at most 500 Mapping occurrences and 500 unique Mappings. The exporter rejects,
rather than truncates, a document exceeding 10,000 endpoints, 500,000 columns, 250,000 table edges,
250,000 column edges, 500,000 target-column derivations, or a 64 MiB serialized attachment. These
limits bound request-local indexes and serialization memory while preserving complete accepted
documents.

## Physical datastore identity

An ODI component alias identifies one Mapping occurrence, not a database object. The datastore's
design name, Logical Schema, Model, and selected Context are also provenance rather than the final
physical table identity.

For matching within an export, a physical table is identified by the exact, case-preserving tuple:

```text
(technology, dataServer, catalog, schema, resourceName)
```

`catalog` or `schema` can be `null` when the ODI Technology does not support that namespace.
`physicalSchema`, `logicalSchema`, and `modelName` explain how ODI resolved the endpoint but do not
replace the physical tuple. An unresolved topology field remains `null`; neither exporter nor any
consumer may guess it.

A future OpenMetadata importer must require explicit configuration mapping an ODI Data Server to an
OpenMetadata Database Service and database. It must resolve the exported schema, resource name, and
column names against existing OpenMetadata Table entities instead of concatenating names with dots.

## Context and repeated Mapping occurrences

The selected Context resolves each Logical Schema to a Physical Schema and Data Server. The
`declaredContextCode` on a Load Plan occurrence is retained separately because it may be absent or
different from the Context chosen for the preview and export.

One Mapping can occur more than once in a Load Plan or inside a Package. Each occurrence therefore
has its own `occurrenceId`, parent, step path, `RUN_SCENARIO` or `PACKAGE_MAPPING` step type, Scenario
tag, and enabled state. The Mapping graph is stored once in `mappings`; all selected occurrences
refer to it by `mappingId`. Repeated occurrences must not duplicate physical lineage facts during
OpenMetadata conversion.

Repository object IDs and endpoint IDs provide stable references for repeated export from the same
ODI repository. They are still repository-local transport identifiers. OpenMetadata deduplication is
based on resolved physical Table FQNs, never on a Mapping alias or endpoint ID.

## Column derivations

There is exactly one derivation for each exported target-column assignment. `toColumn` references a
column on a `TARGET` endpoint. Each `fromColumns` item references a column on a `SOURCE` endpoint.

| `kind` | `fromColumns` | `complete` | Meaning |
|---|---:|---:|---|
| `SOURCE_COLUMNS` | one or more | `true` or `false` | The target value depends on the listed source columns. `false` means only a safe partial result was resolved. |
| `NULL_LITERAL` | empty | `true` | Every non-empty target expression is exactly the literal `NULL`, ignoring surrounding whitespace and case. |
| `SOURCELESS_EXPRESSION` | empty | `true` | A non-empty expression or positively resolved non-column reference supplies the value without a table-column dependency. |
| `UNMAPPED` | empty | `true` | The active target column has no Mapping assignment. |
| `INACTIVE` | empty | `true` | The target assignment is disabled in ODI. |
| `UNKNOWN` | empty | `false` | The extractor could not classify the assignment safely, for example because expression text was blank or an XRef resolved to no SDK object. |

An empty source list alone never proves `NULL_LITERAL`. ODI 14.1.2 exposes target text through
`MapExpression.getText()`; the extractor classifies `NULL_LITERAL` only when all non-empty expression
texts normalize exactly to `NULL`. The text itself is not exported. A non-column XRef must resolve to
an SDK object before it is treated as a complete source-less expression; an unresolved XRef remains
`UNKNOWN`. This prevents variables, sequences, functions, failed SDK reads, and traversal limits from
being mislabeled as null literals.

Transformation components such as Filter, Join, Expression, Aggregate, Distinct, Set, Dataset, and
Reusable Mapping signatures are used internally to resolve dependencies. They never appear as
endpoints and must never become synthetic tables in OpenMetadata.

## Proposed OpenMetadata conversion

A future importer should process a complete document as follows:

1. Validate the document against the v1 schema and resolve every endpoint through explicit service
   mapping and existing OpenMetadata Table metadata.
2. Reject or report ambiguous and missing tables or columns. OpenMetadata can silently discard
   invalid column FQNs, so an HTTP success alone does not prove a complete import.
3. Aggregate all selected Mapping occurrences by physical `(from table, to table)` pair before
   writing. Multiple occurrences or aliases of the same physical object must not create duplicates.
4. For `SOURCE_COLUMNS`, group source columns by their physical source table and target column. A
   derivation using columns from multiple source tables becomes column lineage on each corresponding
   table-to-table edge because OpenMetadata validates source columns against one upstream table per
   edge.
5. Convert only complete `SOURCE_COLUMNS` derivations into OpenMetadata `ColumnLineage` objects.
   `function` remains unset. Incomplete derivations are reported rather than silently presented as
   complete lineage.
6. Preserve explicit `tableEdges` even when they contain no column lineage. Do not create a table or
   column named `NULL`, and do not emit a source-less OpenMetadata column edge.

OpenMetadata's current `ColumnLineage` model requires one or more upstream column FQNs and one target
column FQN on a table-to-table edge. It has no source-less column node. Consequently,
`NULL_LITERAL`, `SOURCELESS_EXPRESSION`, `UNMAPPED`, and `INACTIVE` remain truthful export facts but
produce no OpenMetadata column edge. A target generated entirely from constants would require a
separate future decision about Pipeline-to-Table lineage; it must not be represented by a fake table.

## Re-import and reconciliation

OpenMetadata upserts an existing lineage relationship for the same upstream and downstream entity
pair, so a future importer must aggregate the document before writing. Two selected Mappings must not
overwrite each other's columns for the same table pair.

A future importer must define stable snapshot ownership, resolve and validate the entire document
before mutation, and preserve lineage owned by other sources. Re-import must be idempotent and must
define how columns and table pairs that disappear from a later export are removed.

OpenMetadata stores one Pipeline reference and one lineage-detail document for a physical table pair.
Two Load Plans cannot retain independent ownership or different column mappings for the same pair
without an aggregation policy. Transactional replacement and recovery after partial writes remain
requirements for any future implementation.

## Sensitive-data boundary

The export must never contain:

- JDBC URLs, usernames, passwords, access tokens, or session identifiers;
- Master or Work Repository connection properties;
- raw ODI expressions or SQL;
- literal values;
- Java SDK objects or vendor installation paths.

The allowed `source`, Load Plan, Scenario, topology, table, and column fields are design-time
metadata required to reproduce lineage resolution.
