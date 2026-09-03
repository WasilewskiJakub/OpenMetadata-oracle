# Move the ODI lineage workspace to an Intel Mac

This runbook moves the current source checkpoint and the personal Oracle Linux lab to an Intel Mac.
It deliberately keeps Oracle binaries, VM images, imported repository archives, SSH private keys,
and passwords outside Git.

## What moves where

| Artifact | Transfer method | Destination |
|---|---|---|
| OpenMetadata and ODI explorer source | Private Git remote | Git checkout on the Mac |
| Oracle Linux, Database, ODI, and repository state | Private OVA transfer | VirtualBox on the Mac |
| ODI SDK client JARs | SCP from the imported VM | `~/.local/share/oracle/odi/14.1.2/lib` |
| SSH access | New Mac-specific Ed25519 key | Private key only in macOS `~/.ssh` |
| Repository passwords | Manual entry or macOS Passwords/Keychain | Never Git or a project file |

Do not commit `*.jar`, `INITIAL_REPO*.zip`, Oracle installation media, `.ova`, `.ovf`, `.vdi`,
or `.vmdk`. The largest required ODI client JAR is over GitHub's regular 100 MiB file limit, and
Git LFS would not change the Oracle license terms. The project therefore commits source, expected
checksums, and a transfer script, not Oracle binaries.

## 1. Export the powered-off VM on Windows

1. Close ODI Studio and SQL Developer.
2. Use the verified shutdown script if it exists at `/home/kuba/bin/odi-lab-shutdown`. If the local
   copy is named `/home/kuba/my-shutdown.sh`, use that path instead. The manual fallback is:

   ```bash
   sudo systemctl stop dbora.service
   sudo shutdown -h now
   ```

3. Wait until VirtualBox reports `OracleLinux8` as **Powered Off**. Do not export a running, paused,
   or saved-state database VM.
4. In VirtualBox select **File → Export Appliance**, select `OracleLinux8`, and use:

   ```text
   Format:             Open Virtualization Format 2.0
   Output:             OracleLinux8-odi14c-ready-20260903.ova
   Write Manifest:     enabled
   Include ISO images: disabled
   ```

5. Leave MAC-address handling at its default. New addresses can be generated during import.
6. Keep the OVA on a private or encrypted disk. It contains the database, repository state, and
   proprietary Oracle software.

Equivalent PowerShell export:

```powershell
$vb = "C:\Program Files\Oracle\VirtualBox\VBoxManage.exe"

& $vb export "OracleLinux8" `
  --output "D:\VM-exports\OracleLinux8-odi14c-ready-20260903.ova" `
  --ovf20 `
  --manifest
```

Record the OVA checksum before copying it:

```powershell
Get-FileHash `
  "D:\VM-exports\OracleLinux8-odi14c-ready-20260903.ova" `
  -Algorithm SHA256
```

OVA is the right artifact for the current powered-off state. Treat it as a flattened checkpoint,
not as a transfer of the VirtualBox snapshot tree. If snapshot history is required, preserve the
complete VM folder separately; it is not needed to resume this project.

## 2. Prepare the Intel Mac and import the VM

Confirm that the Mac is Intel-based:

```bash
uname -m
```

The expected output is `x86_64`. Install the Intel build of VirtualBox and Docker Desktop. Before
importing, verify the transferred OVA:

```bash
shasum -a 256 OracleLinux8-odi14c-ready-20260903.ova
```

The result must match the PowerShell SHA-256 exactly. Then select **File → Import Appliance**, choose
the OVA, and review the VM resources before completing the import.

The Windows lab used 16 GB RAM and 10 vCPUs. Keep those values only if the Mac has at least 32 GB RAM
and enough logical CPUs. Otherwise start with 10–12 GB RAM and 4–6 vCPUs, and avoid running ODI Studio
and the full OpenMetadata stack simultaneously. Keep VMSVGA, 128 MB video memory, and 3D acceleration
disabled.

## 3. Recreate and verify VirtualBox NAT forwarding

Set Adapter 1 to **NAT**, then open **Advanced → Port Forwarding** and create or verify:

| Name | Protocol | Host IP | Host port | Guest IP | Guest port |
|---|---|---:|---:|---|---:|
| `odi-ssh` | TCP | `127.0.0.1` | `2222` | blank | `22` |
| `odi-db-native` | TCP | `127.0.0.1` | `1521` | blank | `1521` |
| `odi-db` | TCP | `127.0.0.1` | `15210` | blank | `1521` |

Port `15210` is used for the initial Master Repository JDBC connection. Port `1521` is also required
because the Work Repository URL stored in Master uses `odi14c-lab:1521/odipdb`. If macOS already uses
port 1521, resolve that conflict before starting the explorer; changing only the form's JDBC URL will
not change the Work Repository URL stored by ODI.

Start the VM and verify the three host endpoints:

```bash
nc -vz 127.0.0.1 2222
nc -vz 127.0.0.1 1521
nc -vz 127.0.0.1 15210
```

## 4. Create a separate Mac SSH key

Do not copy the WSL private key through Git. Generate a new key on the Mac:

```bash
mkdir -p ~/.ssh
chmod 700 ~/.ssh
ssh-keygen \
  -t ed25519 \
  -f ~/.ssh/openmetadata_odi14c_ed25519 \
  -C "openmetadata-odi14c-macos"
```

Using the VirtualBox console, append only the new `.pub` line to
`/home/odi-dev/.ssh/authorized_keys`. Then run in the guest as `root`:

```bash
chown -R odi-dev:odi-dev /home/odi-dev/.ssh
chmod 700 /home/odi-dev/.ssh
chmod 600 /home/odi-dev/.ssh/authorized_keys
restorecon -RFv /home/odi-dev/.ssh
```

Test from macOS:

```bash
ssh \
  -i ~/.ssh/openmetadata_odi14c_ed25519 \
  -p 2222 \
  odi-dev@127.0.0.1
```

## 5. Restore the ODI SDK JARs without Git

The OVA contains the existing transfer directory `/home/odi-dev/odi-sdk-transfer`. From the cloned
repository on the Mac run the committed script with Bash, not `sh`:

```bash
bash tools/odi-lineage-explorer/scripts/fetch-odi-sdk-jars.sh
```

Defaults on macOS are already correct:

```text
VM host:      127.0.0.1
VM SSH port:  2222
VM user:      odi-dev
SSH key:      ~/.ssh/openmetadata_odi14c_ed25519
Remote files: /home/odi-dev/odi-sdk-transfer
Local files:  ~/.local/share/oracle/odi/14.1.2/lib
```

The script downloads the three ODI client libraries, `ojdbc11.jar`, and `checksums.sha256` to a
temporary directory, verifies every SHA-256 with macOS `shasum`, refuses to overwrite existing files,
and installs the results with mode `600`. Override a value only through an environment variable, for
example:

```bash
SSH_KEY=~/.ssh/a-different-key \
  bash tools/odi-lineage-explorer/scripts/fetch-odi-sdk-jars.sh
```

`INITIAL_REPO_DEVTS.zip` is not needed on the Mac because its content is already imported into the
repository carried by the VM.

## 6. Clone the exact source checkpoint

```bash
git clone git@github.com:WasilewskiJakub/OpenMetadata-oracle.git
cd OpenMetadata-oracle
git fetch --all --tags --prune
git switch codex/odi-lineage-explorer
git show --no-patch odi-lineage-explorer-v0.1-checkpoint
git status --short
```

The final command should be empty before new work starts. The branch is convenient for continued
development; the annotated tag is the immutable rollback point.

## 7. Prepare the development toolchain

The repository setup script supports Intel macOS:

```bash
./scripts/dev_setup.sh --check
```

For explorer-only work, use the smaller setup:

```bash
./scripts/dev_setup.sh --slim -y
source .dev-env.local.sh
```

Before implementing the OpenMetadata ingestion connector, use the full setup instead:

```bash
./scripts/dev_setup.sh -y
source .dev-env.local.sh
```

The native host is the simplest place to run the explorer because VirtualBox forwards the database
to macOS loopback. A DevContainer sees its own loopback; if it is used later, explicitly route the
VM endpoints through the Docker host rather than assuming `127.0.0.1` means macOS.

## 8. Start the explorer on macOS

Create the ignored file `tools/odi-lineage-explorer/.local/hosts`:

```text
127.0.0.1 localhost
::1 localhost
127.0.0.1 odi14c-lab
```

Terminal 1, from `tools/odi-lineage-explorer`:

```bash
MAVEN_OPTS="-Djdk.net.hosts.file=$PWD/.local/hosts" \
  ODI_EXPLORER_PORT=8787 \
  mvn -f backend/pom.xml exec:java
```

Terminal 2, from the same directory:

```bash
yarn --cwd frontend install --frozen-lockfile
VITE_API_MODE=http \
  VITE_BACKEND_TARGET=http://127.0.0.1:8787 \
  yarn --cwd frontend dev
```

Open <http://localhost:5173> and enter:

```text
Master JDBC: jdbc:oracle:thin:@//127.0.0.1:15210/odipdb
```

Enter passwords manually for each session. For this lab, Load Plan names containing `MIGRACJA` use
Context `MIG_CSIRE_DEV`; all others use `DEV`.

When the OpenMetadata stack is needed:

```bash
docker compose -f docker/development/docker-compose.yml up -d
docker compose -f docker/development/docker-compose.yml ps
```

OpenMetadata is then available at <http://localhost:8585>.

## 9. Resume checklist

- VM boots and `ODIPDB` returns to `READ WRITE` through its existing startup service.
- Ports 2222, 1521, and 15210 are reachable on macOS loopback.
- The new Mac SSH key logs in as `odi-dev`.
- The JAR transfer script reports all checksums as valid.
- `mvn -f tools/odi-lineage-explorer/backend/pom.xml spotless:check verify` passes.
- `yarn --cwd tools/odi-lineage-explorer/frontend test:run` and `build` pass.
- The browser shows `GOSIA_COUNTRY_SRC` with six column edges and `D_PP_AU_SRC_MAP` with 27 in
  `MIG_CSIRE_DEV`, without incomplete-lineage warnings.
- No password, private key, Oracle JAR, OVA, or `INITIAL_REPO*.zip` appears in `git status`.

## Official references

- [VirtualBox import and export](https://docs.oracle.com/en/virtualization/virtualbox/7.1/user/EN-VBOX-7-1-USER.pdf)
- [VirtualBox NAT and port forwarding](https://docs.oracle.com/en/virtualization/virtualbox/7.2/user/networkingdetails.html)
- [Docker Desktop for Mac](https://docs.docker.com/desktop/setup/install/mac-install/)
- [GitHub large-file limits](https://docs.github.com/en/repositories/working-with-files/managing-large-files/about-large-files-on-github)
- [Oracle Data Integrator downloads and applicable terms](https://www.oracle.com/middleware/technologies/data-integrator-downloads.html)
