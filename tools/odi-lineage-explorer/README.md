# ODI Lineage Explorer

Standalone, read-only browser for Oracle Data Integrator metadata. It lives in this repository to
share the OpenMetadata development harness and skills, but it is not an OpenMetadata runtime module
and is built and deployed independently.

Current MVP capabilities:

- short-lived, bounded in-memory repository and demo sessions;
- Load Plan and Context browsing;
- Scenario-to-Mapping resolution states;
- per-occurrence Mapping selection;
- source-left/target-right table and column lineage with expandable columns, relationship
  highlighting, zoom, and an accessible table alternative;
- separate component alias, datastore name, resource name, Model, Logical Schema, and resolved
  physical location metadata.

The mapping view exposes only real bound source and target datastores. ODI Filter, Join, Expression,
Aggregate, Distinct, Set, signature, and Reusable Mapping components are used internally to resolve
dependencies and never appear as synthetic tables. Repeated and nested Reusable Mapping instances use
scope-aware transport IDs so their internal endpoints do not merge.

There is deliberately no JSON/XML export and no OpenMetadata ingestion integration yet. The JSON
used by the internal REST API is a UI transport only and is not an export contract.

## Status and safety boundary

The backend wires the real `OdiSdkProviderFactory` and keeps `DemoOdiReadProvider` behind a separate
demo action. In HTTP mode, the connection form sends credentials to the local backend only for the
current in-memory session. No credentials are written to files, browser storage, logs, or an
application database. Request-scoped copies are cleared after setup. One session-scoped repository
buffer remains in memory because ODI 14.1.2 keeps the supplied array in its pool configuration; it is
cleared when the session closes. A failed connection clears it during cleanup, while a permanently
blocked connection remains bounded by the global SDK-session limit.

The SDK adapter uses `OdiInstance.createInstance(config)`, bounded connection pools, one bounded SDK
executor per session, and `getTransactionalEntityManager()` without starting a transaction. Production
code contains no ODI persistence, transaction, generation, execution, or import calls. A successful
login against the lab repository is verified with 13 Contexts and 15 Load Plans. Production acceptance
still requires a dedicated no-DML database identity and audit evidence.

Read [docs/architecture.md](docs/architecture.md) before changing the SDK boundary. It records the
verified ODI 14.1.2 APIs, forbidden write operations, Load Plan traversal rules, and deferred
identity/export decisions.

ODI 14.1.2 is the primary and currently tested target. The adapter boundary is kept suitable for a
future ODI 12c implementation, but 12c compatibility is not claimed without separate JARs and tests.

## Layout

```text
backend/   independent Java 21 REST service
frontend/  independent React, TypeScript, and Vite application
docs/      architecture and security decisions
```

`tools/odi-extractor` remains the private-classpath SDK probe. Oracle libraries stay outside Git at:

```text
~/.local/share/oracle/odi/14.1.2/lib/
```

They are required to compile and run the real provider, but are not needed by the browser-only demo.
They must never be packaged into a distributable artifact.

After importing the ODI lab VM on another machine, restore the private SDK cache directly from the
guest rather than Git:

```bash
bash tools/odi-lineage-explorer/scripts/fetch-odi-sdk-jars.sh
```

The script defaults to VirtualBox NAT at `127.0.0.1:2222`, verifies the checksum manifest supplied by
the VM transfer directory, supports macOS `shasum`, and refuses to overwrite an existing
cache. The complete Intel Mac migration procedure is in
[`docs/odi-lineage-explorer-macos-intel.md`](../../docs/odi-lineage-explorer-macos-intel.md).

ODI instance initialization also requires `org.json.JSONTokener`; the backend declares the public
`org.json:json` dependency explicitly. The three Oracle client libraries do not contain that class.

## Run the integrated demo

Prerequisites: Java 21, Maven 3.9+, Node 22, and Yarn 1.22.

Terminal 1, from this directory:

```bash
mvn -f backend/pom.xml exec:java
```

Terminal 2:

```bash
cd frontend
yarn install --frozen-lockfile
VITE_API_MODE=http yarn dev
```

Open <http://localhost:5173>. Vite proxies `/api` to the backend on loopback port `8080`.

Use another backend port without editing source:

```bash
ODI_EXPLORER_PORT=8787 mvn -f backend/pom.xml exec:java
VITE_API_MODE=http VITE_BACKEND_TARGET=http://127.0.0.1:8787 yarn --cwd frontend dev
```

For the current VirtualBox lab, the Work Repository resource stored in Master uses
`odi14c-lab:1521`. Add the `odi-db-native` NAT rule documented in the lab runbook and create the
ignored `.local/hosts` file:

```text
127.0.0.1 localhost
::1 localhost
172.28.48.1 odi14c-lab
```

Start the backend with that local mapping:

```bash
MAVEN_OPTS="-Djdk.net.hosts.file=$(pwd)/.local/hosts" \
  ODI_EXPLORER_PORT=8787 \
  mvn -f backend/pom.xml exec:java
```

For a frontend-only preview, run `yarn dev` without `VITE_API_MODE`; it uses the in-browser demo
adapter and still does not persist credentials.

The current VirtualBox lab exposes Oracle through `172.28.48.1:15210` to guest port `1521`, so its
JDBC URL is `jdbc:oracle:thin:@//172.28.48.1:15210/odipdb`.

## Verify

```bash
mvn -f backend/pom.xml spotless:check verify

cd frontend
yarn install --frozen-lockfile
yarn test:run
yarn test:coverage
yarn lint:e2e
yarn test:e2e
yarn build
```

Install the Playwright Chromium binary once with `yarn playwright install chromium` when it is not
already available. The backend and frontend both enforce at least 90% line coverage. Both
applications are intentionally excluded from the root Maven reactor and OpenMetadata deployment.
