# ODI 14c Mapping lineage — Codex hand-off

Last updated: 2026-08-31

## Current state

The connector has not been implemented and the ODI lab has not been installed. Research and architecture
decisions are complete enough to start preparing a test environment.

The broader repository audit is in docs/odi-connector-readiness-analysis.md. This file contains the latest
working decisions from the follow-up discussion.

## Connector scope selected

- Target ODI release: 14c, version 14.1.2.0.0.
- First capability: design-time lineage from ODI Mappings only.
- Represent each ODI Mapping as an OpenMetadata Pipeline.
- Emit precise source-table to target-table lineage attributed to that Mapping.
- Emit column-level lineage and expressions when the ODI SDK resolves them unambiguously.
- Support multiple source columns, reusable Mappings, and an explicit ODI Context.
- Resolve datastores to exact existing OpenMetadata database-service FQNs.
- Skip ambiguous edges instead of guessing.

Deferred from the first version:

- Load Plans;
- session and task execution status;
- schedules;
- Packages and Procedures;
- runtime OpenLineage emission.

Load Plans are orchestration/control flow over Scenarios. They do not add transformation semantics that
justify duplicating the lineage already attributed to individual Mappings. They may be modeled later as
separate pipeline metadata.

## Access architecture selected

Use the official ODI Java SDK as the source of design-time metadata. Keep it isolated from the Python
ingestion runtime:

    ODI 14c SDK and repository
        -> version-specific Java extractor
        -> versioned JSON or NDJSON
        -> Python OpenMetadata pipeline connector

Repository SQL and Smart Export XML remain fallback approaches, not the initial implementation.

Oracle SDK JARs must remain in the ODI development VM. Do not commit, redistribute, or package them with
OpenMetadata. Extractor source and sanitized JSON fixtures may be kept in this repository.

## Assessment of info4j/odilineage

Repository: https://github.com/info4j/odilineage

Useful conclusion: the project proves that ODI SDK exposes Mappings, targets, attributes, expressions,
and bound object names.

Do not adopt it as a dependency, fork, or production parser:

- all three commits and its single release are from October 2020;
- it is a Java 8 NetBeans/Ant desktop application exporting Excel;
- it depends on a local ODI 12c library directory;
- it derives one source column from MapAttribute technical-description text instead of traversing the
  full upstream component and attribute graph;
- that approach is insufficient for joins and multi-source expressions;
- reusable Mapping handling is commented out;
- it lacks Context/FQN resolution and runtime/orchestration coverage;
- its connection helper logs repository and ODI credentials;
- its LGPL-2.1 source would require a separate licensing review before reuse.

Use it only as an SDK feasibility reference. Build the lineage traversal from official Oracle APIs.

## Development lab decision

Preferred environment: manually installed ODI 14c Standalone VM.

Choose a VM before Docker because Oracle's public Docker repository contains current Fusion Middleware
Infrastructure 14.1.2 recipes but only archived ODI recipes for older 12.2.1.2.6 and 12.2.1.3.0 releases.
A custom ODI 14c container would add silent-installer, repository-creation, licensing-media, and GUI
automation work before lineage development can begin.

After a manual installation works, record installer response files and consider repeatable silent-mode
automation.

Official references:

- https://docs.oracle.com/en/middleware/fusion-middleware/14.1.2/oding/index.html
- https://docs.oracle.com/en/middleware/fusion-middleware/14.1.2/oding/preparing-install-and-configure-product.html
- https://docs.oracle.com/en/middleware/fusion-middleware/14.1.2/oding/installing-product-software.html
- https://github.com/oracle/docker-images/tree/main/Archive/OracleDataIntegrator
- https://github.com/oracle/docker-images/tree/main/OracleFMWInfrastructure/dockerfiles/14.1.2.0

## OVA decision

An OVA is a packaged virtual appliance containing VM configuration and virtual disks. It is not
necessarily Linux and is not an operating-system installer like an ISO.

Use an ODI lab OVA as the primary lab only if its manifest confirms ODI 14.1.2.0.0 and the appliance has
acceptable licensing and the required SDK/repository topology. An ODI 12c OVA may be useful for learning,
but it cannot provide ODI 14c SDK compatibility evidence.

Current choice: create a clean VM and install ODI 14c manually so every location, dependency, and
configuration decision is understood and documented.

## Confirmed installation inputs

- ODI release: 14.1.2.0.0.
- Installer named by Oracle: fmw_14.1.2.0.0_odi_generic.jar.
- Oracle documentation states JDK 17.0.12 or later for the 14c installer.
- Planned installer choice: Standalone Installation.

Still verify against Oracle's current certification matrix before installation:

- guest operating-system release;
- repository database release;
- whether the selected Standalone path needs a separate Fusion Middleware Infrastructure installation.

Record exact filenames, versions, download sources, and SHA-256 checksums during installation. Never
record Oracle credentials or add installation media to Git.

## VM access for Codex

Codex can work inside the VM through SSH. For VirtualBox, the simplest planned network is NAT port
forwarding:

    host 127.0.0.1:2222 -> guest TCP 22

Expected command shape:

    ssh -p 2222 odi-dev@127.0.0.1

Use a dedicated odi-dev account with SSH-key authentication. It should have:

- read access to ODI Oracle Home and SDK libraries;
- write access to a dedicated extractor build directory;
- network access to the development Master and Work Repository;
- no routine sudo privileges.

ODI Studio GUI setup remains a manual action. Through SSH, Codex can inventory JARs, run jar and javap,
compile and execute the extractor, query test Mappings through the SDK, and retrieve sanitized NDJSON.

## Security and file boundary

Keep only inside the VM:

- Oracle installation media and SDK JARs;
- ODI and repository passwords;
- wallets and SSH private keys;
- unrestricted repository exports.

Safe repository artifacts:

- extractor source;
- intermediate JSON schema;
- sanitized fixtures;
- JAR names, versions, paths, checksums, and class inventories;
- commands containing placeholders rather than secrets.

Machine-specific addresses and paths may later be stored in an ignored local file. Confirm the path is
ignored before creating it, and never put passwords or private keys there.

## Required Mapping fixtures

Create deterministic test Mappings for:

1. one source table to one target table;
2. direct column projection;
3. an expression using multiple source columns;
4. a join of two source tables;
5. a lookup;
6. aggregate and group-by;
7. a reusable Mapping;
8. two ODI Contexts resolving the same logical schema to different physical schemas.

For each fixture, record the expected source and target tables, columns, expression, Context, and
OpenMetadata FQNs. These expectations become golden tests.

## Installation journal

| Date | Step | Result |
|---|---|---|
| 2026-08-30 | Connector and lab architecture selected | Mapping-only ODI 14c lineage; manual Standalone VM; installation not started |

Append each completed installation step here with commands or installer choices, observed paths, and
verification evidence. Never include secrets.

## Next step

Verify the current Oracle ODI 14c certification matrix and record the selected guest OS and
repository database versions before downloading installation media.
