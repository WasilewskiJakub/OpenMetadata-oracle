# ODI 14c Mapping lineage — Codex hand-off

Last updated: 2026-09-03

## Current state

The OpenMetadata connector has not been implemented. The Oracle Linux VM now has Oracle Database 19c, Oracle JDK 21,
ODI 14.1.2 Enterprise, ODI Studio, RCU repositories, SQL Developer, and the imported
`INITIAL_REPO_DEVTS.zip` project patch. The user verified that ODI Studio connects to the repository and
that the patch import completed successfully.

An independently buildable ODI Lineage Explorer MVP now lives in `tools/odi-lineage-explorer`. Its
Java backend wires the real ODI 14.1.2 SDK provider and its React frontend supports separate real and
demo sessions. A real read-only application session is verified against `CBK_ODI14C_MASTER` and
`DEV_WORKREP`: the backend returned 13 Contexts and 15 Load Plans, and the browser rendered all 15
rows without console errors or browser storage.

The Mapping-detail failure for `GOSIA_COUNTRY_SRC_MAP` was traced to calling `getCatalogName()` for
an Oracle technology that reports catalogs as unsupported. Physical topology reads now respect the
technology's independent catalog/schema capability flags. The false multi-target classification in
`D_PP_AU_SRC_MAP` was traced to the default input/output port shape of every ODI datastore; roles now
come from the SDK's `isSource()` and `isTarget()` graph semantics.

The explorer now projects only real bound source and target datastores. It resolves column-level
dependencies from ODI attribute cross-references through transformations, Dataset composite
attributes, and nested or repeated Reusable Mappings. Reusable instances have scope-aware internal
IDs, so two uses of one definition cannot leak edges into each other. The React graph keeps sources
left and targets right, supports expandable columns, bidirectional click highlighting, visible
table/column arrows, zoom from 50% to 200%, a text alternative, and explicit incomplete-lineage
warnings. Real-repository visual validation of representative Mapping fixtures is still required.

Real read-only SDK validation after the XRef correction returned complete, warning-free results for
`GOSIA_COUNTRY_SRC` (6 column edges), `D_PP_AU_SRC_MAP` (27 edges, 8 scoped through reusable), and
`D_PP_UKSE_AU_SRC_MAP` (171 edges, 42 scoped through reusable). A `BUCKET_*` mapping containing only a
non-column reference correctly returns no column edges and no false warning. No extra ODI Studio JARs
are required; the existing SDK/common/third-party client libraries contain the model, parser, XRef,
and reusable-signature APIs.

The explicit compatibility exception remains: the database is unpatched 19.3 rather than the documented
RCU minimum 19.14+. RCU nevertheless reported success and created the schemas without bypassing a check.

The broader repository audit is in docs/odi-connector-readiness-analysis.md. This file contains the latest
working decisions from the follow-up discussion. The reusable installation procedure is in
docs/oracle-odi-lab-installation.md. The source, OVA, NAT, SSH, and private-JAR migration procedure
for an Intel Mac is in docs/odi-lineage-explorer-macos-intel.md.

## Development workspace

- Canonical checkout: `/root/workspaces/openmetadata-oracle` on native WSL ext4, owned by `root`.
- Checkpoint branch: `codex/odi-lineage-explorer`; the immutable rollback tag is
  `odi-lineage-explorer-v0.1-checkpoint` after the verified commits are created.
- `.agents`, `.claude`, `.codex`, durable `.serena` state, `skills`, and `docs/codex-work` are tracked.
- Serena 1.7.0 passed symbol overview, lookup, and reference health checks with Python, TypeScript,
  and Java enabled; its global registry contains only the canonical root checkout.
- The root DevContainer completed UI build, ingestion install, model generation, and all prerequisite
  checks with Java 21, Maven 3.9.9, Node 22, Yarn 1.22, Python 3.11, and ANTLR 4.9.2.
- `apply_patch` passed create, update, and delete against the root-owned checkout.
- Current development state: the VM, DevContainer, OpenMetadata Compose services, explorer frontend,
  final verified backend, and the two loopback/proxy containers are running. The integrated browser
  demo reached the HTTP backend with one source, one target, one table edge, two column edges, no
  transformation nodes, no browser storage, and no console errors.
- All temporary credential files and diagnostic probe sources/classes were removed from WSL and the
  DevContainer. The ignored `tools/odi-lineage-explorer/.local/hosts` remains; it contains no secrets.

## Current staged scope

- Primary target: ODI 14c, version 14.1.2.0.0. Keep the SDK boundary adaptable to ODI 12c,
  but do not claim 12c compatibility until it has its own JAR set and integration tests.
- First product: a separately deployable, read-only ODI Lineage Explorer kept in this repository so it
  shares the project skills and hand-off context.
- Browse Load Plans as their real Serial/Parallel/Case/Run Scenario hierarchy.
- Resolve exact Scenario tags to direct Mappings and Package `StepMapping` occurrences.
- Keep repeated Mapping occurrences distinct and expose resolved, stale, unresolved, and out-of-scope
  states without guessing.
- Preserve component alias, ODI datastore name, physical resource name, Model, Logical Schema, selected
  Context, Physical Schema, Data Server, catalog, and schema as separate values.
- Render only bound source/target datastores. Traverse Filter, Join, Lookup, Expression, Aggregate,
  Distinct, Set, Dataset, signature, and Reusable Mapping objects internally to derive precise
  source-column to target-column dependencies; never publish them as tables.
- Keep every Reusable Mapping instance scoped independently, including nested instances.
- Lab Context convention for validation: a Load Plan whose name contains `MIGRACJA` uses
  `MIG_CSIRE_DEV`; every other Load Plan uses `DEV`. This is lab-specific and must not become a
  universal ODI naming rule.
- Procedures and variables remain visible as out of scope; they do not produce lineage.
- The checked-in backend has separate real SDK and demo providers. The real provider is enabled for
  local 14c lab validation and contains only query APIs; production acceptance still requires a
  database-enforced read-only identity and audit evidence.

Deferred until representative repository data is visible in the explorer:

- JSON, NDJSON, XML, or any other export format;
- stable external IDs, OpenMetadata FQNs, deduplication keys, and repeated-import update semantics;
- the OpenMetadata connector and entity writes;
- final exported column FQNs and optional function/expression serialization;
- session and task execution status;
- schedules;
- Procedure lineage;
- runtime OpenLineage emission.

The future OpenMetadata contract must be designed only after observing real aliases, Context resolution,
repeated Mapping occurrences, stale Scenarios, and multi-source/multi-target mappings. Re-importing the
same logical object must update it rather than create a duplicate, but that identity contract is
deliberately not being guessed during the explorer MVP.

## Access architecture selected

Use the official ODI Java SDK as the source of design-time metadata. Keep it isolated inside the
standalone explorer backend and outside the Python ingestion runtime:

    ODI 14c SDK and repository
        -> version-specific Java read-only adapter
        -> internal REST view DTOs
        -> React ODI Lineage Explorer

The internal REST JSON is not an export format and carries no OpenMetadata compatibility promise.
Repository SQL and Smart Export XML remain fallback approaches. A future export and Python connector
form a separate design phase.

Oracle SDK JARs must remain outside Git. They may stay in the ODI development VM or be copied to the
private WSL cache `/root/.local/share/oracle/odi/14.1.2/lib`; do not redistribute or package them with
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

Preferred environment: manually installed ODI 14c development VM.

Choose a VM before Docker because Oracle's public Docker repository contains current Fusion Middleware
Infrastructure 14.1.2 recipes but only archived ODI recipes for older 12.2.1.2.6 and 12.2.1.3.0 releases.
A custom ODI 14c container would add silent-installer, repository-creation, licensing-media, and GUI
automation work before lineage development can begin.

The Enterprise feature set supplies ODI Studio and the materialized SDK client libraries. Do not create
a WebLogic/Jakarta EE domain, AdminServer, or managed server. A standalone agent without Node Manager is
now planned so imported test Mappings can be executed; it is not required by the SDK extractor itself.
Record sanitized response files and consider repeatable silent-mode automation.

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
- Installed distribution: Oracle eDelivery `V1045400-01.zip`, containing
  `fmw_14.1.2.0.0_odi.jar`.
- Guest: Oracle Linux 8.10 x86-64 with UEK 5.15.0-323.211.3.5.el8uek.x86_64.
- Installed database: Oracle Database 19c Enterprise Edition base 19.3, intentionally without an RU.
  Fusion Middleware 14.1.2 RCU requires 19c 19.14 or later, so this is an unsupported lab deviation;
  do not ignore an RCU version failure silently.
- Installed JDK: Oracle JDK 21.0.12.1 at `/u01/app/oracle/product/java/current`; ODI/FMW 14.1.2 supports
  JDK 21.0.4 or later.
- Installed type: Enterprise Installation in `/u01/app/oracle/product/fmw/14.1.2/odi`. The ODI bundle
  laid down the required Infrastructure, ODI Studio, RCU, and materialized client libraries in one home;
  no separate Infrastructure media was needed.
- RCU created the ODI repository schemas successfully. Verified repository identifiers are:
  Master schema `CBK_ODI14C_MASTER`, Work schema `CBK_ODI14C_WORK`, and Work Repository
  `DEV_WORKREP`.

Record exact filenames, versions, download sources, and SHA-256 checksums during installation. Never
record Oracle credentials or add installation media to Git.

## VM access from WSL

The user-operated WSL terminal can reach the VM through VirtualBox NAT forwarding. The managed Codex
runtime cannot currently start SSH directly, so VM commands and file transfers remain user-operated:

    Windows WSL adapter 172.28.48.1:2222 -> guest TCP 22
    Windows WSL adapter 172.28.48.1:15210 -> guest TCP 1521
    Windows WSL adapter 172.28.48.1:1521 -> guest TCP 1521

Expected command shape:

    ssh -i /root/.ssh/openmetadata_odi14c_ed25519 -p 2222 odi-dev@172.28.48.1

The Windows WSL-adapter address can change after WSL networking restarts; recheck it before diagnosing
a refused connection.

Use a dedicated odi-dev account with SSH-key authentication. It should have:

- read access to ODI Oracle Home and SDK libraries;
- write access to a dedicated extractor build directory;
- network access to the development Master and Work Repository;
- no routine sudo privileges.

### SSH bootstrap checklist

The dedicated client key was generated in WSL without changing the existing `id_ed25519` key:

```text
Private key: /root/.ssh/openmetadata_odi14c_ed25519
Public key:  /root/.ssh/openmetadata_odi14c_ed25519.pub
Fingerprint: SHA256:4lcrHEs3lXWDeCQltoCKKPncJ4Y9qgfgsSS7xU5PeSs
```

The private key is mode `600`; never copy its contents into this repository or the VM. The public key
that belongs in `/home/odi-dev/.ssh/authorized_keys` is:

```text
ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIDjXzGil2XGD6Eye1rDAPXDilJxnLMId5nkJI6+Gt6ep openmetadata-odi14c-lab
```

On the Oracle Linux VM, run these steps as `root`:

1. Install and start SSH and the firewall:

   ```bash
   dnf install -y openssh-server firewalld
   systemctl enable --now sshd
   systemctl enable --now firewalld
   firewall-cmd --permanent --add-service=ssh
   firewall-cmd --reload
   sshd -t
   ```

2. Create the unprivileged working account and its SSH directory:

   ```bash
   useradd --create-home --shell /bin/bash odi-dev
   install -d -m 700 -o odi-dev -g odi-dev /home/odi-dev/.ssh
   vi /home/odi-dev/.ssh/authorized_keys
   ```

3. Paste only the public-key line shown above, then fix ownership, permissions, and SELinux labels:

   ```bash
   chown odi-dev:odi-dev /home/odi-dev/.ssh/authorized_keys
   chmod 600 /home/odi-dev/.ssh/authorized_keys
   restorecon -RFv /home/odi-dev/.ssh
   ```

4. Restart and verify SSH:

   ```bash
   systemctl restart sshd
   systemctl --no-pager status sshd
   ss -lntp | grep ':22'
   ```

5. Configure the first VirtualBox adapter as NAT with this port-forwarding rule:

   ```text
   Name:       odi-ssh
   Protocol:   TCP
   Host IP:    172.28.48.1
   Host port:  2222
   Guest IP:   leave empty
   Guest port: 22
   ```

   Add a second rule for the Oracle listener:

   ```text
   Name:       odi-db
   Protocol:   TCP
   Host IP:    172.28.48.1
   Host port:  15210
   Guest IP:   leave empty
   Guest port: 1521
   ```

   Add a third rule because the Work Repository connection stored in the Master Repository uses
   `odi14c-lab:1521`:

   ```text
   Name:       odi-db-native
   Protocol:   TCP
   Host IP:    172.28.48.1
   Host port:  1521
   Guest IP:   leave empty
   Guest port: 1521
   ```

After VirtualBox networking works, verify from WSL with:

```bash
ssh -i /root/.ssh/openmetadata_odi14c_ed25519 -p 2222 odi-dev@172.28.48.1
```

Do not disable SELinux or the firewall, enable SSH root login, or add `odi-dev` to `wheel`. The
`oracle` operating-system account and its installation groups now exist because they were created by the
Oracle Database preinstallation RPM; do not recreate or renumber them manually.

ODI Studio runs as `oracle` from the `kuba` desktop through `/home/kuba/bin/odi-studio`. Through SSH,
the user can inventory or transfer JARs and run controlled SDK probes. NDJSON and every other export
format remain deferred.

### Verified VM and database state

- VirtualBox VM name: `OracleLinux8`; NAT adapter with `odi-ssh`, `odi-db`, and `odi-db-native`
  forwarding rules.
- Guest hostname: `odi14c-lab`.
- Resources after tuning: 10 vCPUs, 16 GB assigned RAM (15 GiB visible), a 16 GiB `/swapfile`, and the
  original 1 GiB swap partition. VirtualBox uses VMSVGA, 128 MB VRAM, 3D disabled, and KVM
  paravirtualization.
- Storage: one 209 GiB ext4 root filesystem; Oracle software, data, and FRA use `/u01` and `/u02`
  directories on that filesystem.
- Security: SELinux Enforcing; `sshd` active; key login as `odi-dev` verified from WSL; guest
  `firewalld` explicitly allows `1521/tcp`.
- Database home: `/u01/app/oracle/product/19.0.0/dbhome_1`; CDB/SID `ODILAB`; PDB/service `ODIPDB` /
  `odipdb`; listener `LISTENER` on TCP 1521.
- Data and FRA: `/u02/oradata` and `/u02/fra`.
- DBCA secret response file: `/home/oracle/.config/odi-lab/dbca_odilab.rsp`, mode `600`. It contains
  generated lab credentials and must never be copied into Git or agent output.
- Verified repository settings: `ODIPDB READ WRITE`, `JAVAVM VALID`, `AL32UTF8`, `db_files=600`,
  `open_cursors=1600`, `processes=1000`, `session_cached_cursors=100`, `sga_max_size=3G`, and
  `shared_pool_size=512M`.
- Automatic startup: `/etc/init.d/dbora`, SELinux type `initrc_exec_t`, registered through `chkconfig`;
  `dbora.service` was `enabled` and `active` after a real VM reboot.
- The native custom systemd unit failed correctly under SELinux because `init_t` could not execute
  Oracle Home's `default_t` scripts or `su_exec_t`. It was disabled and retained only as
  `/root/oracle-odilab.service.failed-20260901`; do not re-enable it.
- Snapshot name requested after database verification: `01-db19c-base-odilab`. The user reported that
  the VM restarted afterward, but the snapshot-list output was not captured in this session.
- Snapshot `02-odi14c-binaries-no-repo` was requested after ODI binary verification and before RCU; the
  user reported that the VM restarted afterward, but the snapshot-list output was not captured.
- The user reported a clean powered-off VM after the full ODI setup and requested snapshot
  `03-odi14c-ready-initial-repo`; snapshot-list output was not captured, so its existence remains to be
  confirmed at the next start.
- Guest Additions 7.0.18 initially failed because the matching UEK development package was missing.
  `kernel-uek-devel-5.15.0-323.211.3.5.el8uek.x86_64` was installed, `rcvboxadd setup` rebuilt the
  modules, `nomodeset` was removed, and the next boot loaded `vmwgfx` and `vboxguest` with no new
  `soft lockup` entry in the captured kernel log. `vboxsf` stays unloaded unless Shared Folders are used.
- Oracle Management Pack access was observed at its EE default `DIAGNOSTIC+TUNING`. Tuning Pack was not
  established as licensed; do not use Real-Time SQL Monitor. A recommendation to set the CDB parameter
  to `NONE` was given, but completion of that change was not confirmed.

### Verified installation artifacts

- Database media: `/u01/stage/LINUX.X64_193000_db_home.zip`, 3059705302 bytes, SHA-256
  `ba8329c757133da313ed3b6d7f86c5ac42cd9970a28bf2e6233f3235233aa8d8`.
- JDK media: `/u01/stage/jdk-21_linux-x64_bin.tar.gz`, 198949838 bytes, SHA-256
  `12f870b21301b42292558a3f872ce543affa2b86cb6458591c78388c41ddb111`.
- JDK real path: `/u01/app/oracle/product/java/jdk-21.0.12.1`; stable symlink:
  `/u01/app/oracle/product/java/current`.
- ODI media: `/u01/stage/V1045400-01.zip`, 2802140309 bytes, SHA-256
  `2d4b2f7a00a4a5f50231fc105363bcff4c9c77550dfa1b8f7e9f6fc69d2696de`.
- ODI installer JAR: `/u01/stage/odi-14.1.2-media/fmw_14.1.2.0.0_odi.jar`, 2802486715 bytes,
  SHA-256 `4cdca4f11d2e8bfd46faffe6e0853a2d64f43184ef3979e31fd148ce11d985e9`; `jarsigner` reported
  `jar verified`.
- ODI home: `/u01/app/oracle/product/fmw/14.1.2/odi`; OPatch 13.9.4.2.17.
- Materialized clients in `$ODI_HOME/odi/modules/clients`:
  - `oracle.odi.common.clientLib.jar`, 156029282 bytes, SHA-256
    `a1733d9d3a0d86feaa8b87b10b66c866e01fdaab182343c0c7c13aa9af79da9b`;
  - `oracle.odi.tp.clientLib.jar`, 6176282 bytes, SHA-256
    `ba70a29e4fcd8664df2aeae38c7b2575ac0f460a9c5a9ed159d03c361260b6cc`;
  - `oracle.odi.sdk.clientLib.jar`, 85250449 bytes, SHA-256
    `7e4d76dda9866f9684df2050cd54287e73402182c18e12b8cbe129571543bf1c`.
- Private WSL cache also contains `ojdbc11.jar`, 7196593 bytes, SHA-256
  `dc1a3d0fa7c75599e69b310dc7e5226e771c1cf77e060cc0a0e8c19f7e1ef1c5`.
- SQL Developer 26.2 RPM: 420134798 bytes, SHA-256
  `f2366b746f5a42b431448cb07606bde1c47c28398c8d7e74f7665e675ab3fdee`; installed and launched with
  the dedicated JDK.
- ODI Studio launcher for the desktop user: `/home/kuba/bin/odi-studio`; it grants X access to `oracle`
  only for the process lifetime and launches Studio as the Oracle installation owner.
- Smart Import source: `/u01/stage/odi-import/INITIAL_REPO_DEVTS.zip`; the user reported successful
  completion. Its size, checksum, and imported object counts were not captured.
- Oracle account profile defines `JAVA_HOME`, `DB_HOME`, `ORACLE_SID`, and `PATH`, but deliberately does
  not set global `ORACLE_HOME`. Invoke database tools with `ORACLE_HOME="$DB_HOME"`.

### Codex execution status

The current Codex CLI runs in WSL. Its managed `citizen` runtime policy rejects direct remote-shell
commands before OpenSSH starts, even after user approval and an escalation request. Do not try to bypass
this through PowerShell or VirtualBox guest control.

The Windows Codex app is version 26.825.6671.0 and starts a Codex 0.151.0 app server inside Ubuntu WSL.
Windows and the WSL app server both see `/root/workspaces/openmetadata-oracle`; the directory is writable
and is a valid Git repository. Project creation sends `project/import`, but no project record is written,
and the backend log contains no reason for the generic UI failure. The CLI `/app` command is unavailable
because that handoff is supported from native Windows/macOS CLI, not Linux/WSL.

## Security and file boundary

Keep private and outside Git:

- Oracle installation media;
- ODI SDK JARs, either in the VM or the private WSL cache;
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
7. multiple target datastores;
8. a nested reusable Mapping and two instances of the same reusable Mapping;
9. two ODI Contexts resolving the same logical schema to different physical schemas.

For each fixture, record the expected ODI design object IDs, source and target tables, columns,
expression, Context, and resolved physical coordinates. These expectations become explorer golden
tests. OpenMetadata FQNs remain deferred until the export identity contract is designed from real data.

## Installation journal

| Date | Step | Result |
|---|---|---|
| 2026-08-30 | Connector and lab architecture selected | Mapping-only ODI 14c lineage; manual VM selected |
| 2026-09-01 | VM baseline and SSH access verified | Oracle Linux 8.10 healthy; key login works through `172.28.48.1:2222` |
| 2026-09-01 | Windows Codex app WSL project import investigated | WSL agent, path access, write access, and Git are valid; `project/import` fails without a logged cause |
| 2026-09-01 | Database host prepared | Preinstall RPM installed; 16 GiB swap file active; THP disabled; `/u01` and `/u02` layout created |
| 2026-09-01 | Oracle Database software installed | 19c base 19.3 software-only install succeeded; inventory and root scripts completed |
| 2026-09-01 | Development database created | `ODILAB`/`ODIPDB`, listener, JVM, charset, and RCU-oriented parameters verified |
| 2026-09-01 | Database autostart verified | SELinux-safe `dbora` service starts `ODIPDB READ WRITE` and registers `odipdb READY` after VM reboot |
| 2026-09-01 | Dedicated JDK installed | Oracle JDK 21.0.12.1 archive checksum matched Oracle; `java` and `javac` verified |
| 2026-09-01 | ODI Enterprise installed | Signed ODI 14.1.2 installer completed successfully; Studio, RCU, OPatch, and three SDK client libraries verified |
| 2026-09-01 | ODI repositories created | RCU reported Success; user connected ODI Studio to the repository |
| 2026-09-01 | SQL Developer installed | SQL Developer 26.2 package digests matched Oracle and the GUI launched on JDK 21 |
| 2026-09-01 | Initial ODI project patch imported | User reported `INITIAL_REPO_DEVTS.zip` Smart Import completed successfully |
| 2026-09-01 | VirtualBox guest stabilized | 16 GB/10 vCPU; Guest Additions rebuilt for UEK; `nomodeset` removed; `vmwgfx` active |
| 2026-09-02 | End-of-session preservation | User reported the VM powered off cleanly; snapshot `03-odi14c-ready-initial-repo` requested but not independently confirmed |
| 2026-09-02 | ODI Lineage Explorer MVP implemented | Independent Java 21 backend and React UI; 16 backend and 9 frontend tests pass; full HTTP demo flow and responsive UI verified; no export or real repository connection enabled |
| 2026-09-03 | Real ODI 14c reader verified | Master `CBK_ODI14C_MASTER`, Work schema `CBK_ODI14C_WORK`, repository `DEV_WORKREP`; guest firewall opened on 1521; NAT aliases support both external JDBC and the Work URL stored in Master; missing `org.json` and Maven-exec thread classloader fixed; 93 backend and 28 frontend tests pass; real HTTP returned 13 Contexts and 15 Load Plans; browser rendered 15 rows with no storage or console errors |
| 2026-09-03 | Session safely stopped | User reported Mapping-detail `Unexpected error`; backend recorded `DomainRuntimeException`; OpenMetadata Compose services, proxies, and DevContainer stopped without deleting containers, images, or volumes; temporary credentials and probes removed |
| 2026-09-03 | Mapping-detail regressions fixed | `GOSIA_COUNTRY_SRC_MAP` topology resolution now respects technology catalog/schema support; datastore roles use ODI `isSource()`/`isTarget()`; Load Plan structural nodes are accessible collapsible tree nodes |
| 2026-09-03 | Column-lineage preview implemented | Endpoint-only API and UI; table paths collapse hidden transformations and exact reusable signatures; bound column references traverse multiple expressions, MapReference wrappers, Dataset composites, and nested/repeated Reusable Mapping signatures with scope-aware IDs; source-left/target-right diagram, column highlighting, arrows, zoom, bounded large-graph rendering, warnings, and paged accessible table added; 114 backend and 44 frontend tests, coverage gates, build, lint, and Playwright pass; export remains deferred |
| 2026-09-03 | Real column lineage verified | `isValid` was removed as an XRef gate to match ODI bytecode; `GOSIA_COUNTRY_SRC`, `D_PP_AU_SRC_MAP`, `D_PP_UKSE_AU_SRC_MAP`, and five reusable-heavy mappings returned expected table/column edges with zero warnings; non-column BUCKET reference no longer creates a false incomplete alert; all temporary credentials and the unwanted local `INITIAL_REPO_DEVTS.zip` copy were removed |

Append each completed installation step here with commands or installer choices, observed paths, and
verification evidence. Never include secrets.

## Next step

Export the fully powered-off `OracleLinux8` VM to a private OVF 2.0 OVA with a manifest and record its
SHA-256, following section 1 of `docs/odi-lineage-explorer-macos-intel.md`.
