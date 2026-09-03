# Oracle Database 19c and ODI 14c lab on Oracle Linux 8

Last verified: 2026-09-03

This runbook builds a local development lab for Oracle Data Integrator metadata and SDK research.
The two parts are intentionally independent:

- **Part A — Oracle Database 19c:** verified end to end on Oracle Linux 8.10.
- **Part B — Oracle Data Integrator 14.1.2:** Enterprise installation, RCU repository, Studio login,
  SDK libraries, SQL Developer, and an initial Smart Import are verified.
- **Part C — VM operation:** verified VirtualBox sizing, Guest Additions repair, GUI launchers, clean
  shutdown, and snapshot procedure.

The commands use lab defaults that can be replaced through the variables below. Passwords, private
keys, Oracle media, and licensed JARs must never be committed to Git.

> **Compatibility caveat:** the verified lab deliberately runs the public Oracle Database 19c base
> release 19.3 without a Release Update. Fusion Middleware 14.1.2 RCU requires Oracle Database 19c
> 19.14 or later. This is an unsupported lab deviation and RCU may reject it. Use a currently certified
> RU for a supported environment.

## Lab defaults

| Item | Default |
|---|---|
| Operating system | Oracle Linux 8.10 x86-64 |
| Database owner/group | `oracle:oinstall` |
| Oracle base | `/u01/app/oracle` |
| Database home | `/u01/app/oracle/product/19.0.0/dbhome_1` |
| Central inventory | `/u01/app/oraInventory` |
| Installation stage | `/u01/stage` |
| Data/FRA | `/u02/oradata`, `/u02/fra` |
| CDB / SID | `ODILAB` |
| PDB / service | `ODIPDB` / `odipdb` |
| Listener | `LISTENER`, TCP 1521 |
| JDK | Oracle JDK 21.0.12.1 |
| JDK home | `/u01/app/oracle/product/java/current` |
| FMW/ODI home | `/u01/app/oracle/product/fmw/14.1.2/odi` |

## Secret and artifact boundary

- Store DBCA response files under `/home/oracle/.config/odi-lab/` with mode `600`.
- Move durable passwords to a password manager. Do not copy passwords into this document.
- Keep Oracle ZIP/JAR media under `/u01/stage`; do not add it to Git.
- Keep ODI SDK JARs in the VM or a private, ignored WSL cache.
- Commit only sanitized fixtures, public class inventories, versions, paths, and SHA-256 values.

# Part A — Install Oracle Database 19c independently

## A1. Prepare Oracle Linux

Run as `root`:

```bash
dnf install -y \
  oracle-database-preinstall-19c \
  chrony curl unzip tar openssl chkconfig

systemctl enable --now chronyd
timedatectl set-timezone Europe/Warsaw
hostnamectl set-hostname odi14c-lab
```

Create the installation and database directories:

```bash
install -d -o oracle -g oinstall -m 775 \
  /u01/app/oracle \
  /u01/app/oraInventory \
  /u01/stage \
  /u02/oradata \
  /u02/fra
```

For a VM with approximately 12 GB RAM, add a 16 GB swap file without overwriting an existing one:

```bash
if [ ! -f /swapfile ]; then
  fallocate -l 16G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
fi

swapon --show=NAME --noheadings | grep -qx '/swapfile' || swapon /swapfile

grep -Fqx '/swapfile none swap defaults 0 0' /etc/fstab || \
  printf '%s\n' '/swapfile none swap defaults 0 0' >> /etc/fstab
```

Disable Transparent Huge Pages persistently and reboot:

```bash
grubby --update-kernel=ALL --args="transparent_hugepage=never"
reboot
```

After reboot, verify the baseline:

```bash
free -h
swapon --show
cat /sys/kernel/mm/transparent_hugepage/enabled
rpm -q oracle-database-preinstall-19c
id oracle
```

Expected THP state: `always madvise [never]`.

## A2. Obtain and verify the Database 19c base media

Download the official Linux x86-64 Database 19c home archive from Oracle:

```text
LINUX.X64_193000_db_home.zip
```

Verified base-media identity:

```text
Size:   3059705302 bytes
SHA256: ba8329c757133da313ed3b6d7f86c5ac42cd9970a28bf2e6233f3235233aa8d8
```

Place it at `/u01/stage/LINUX.X64_193000_db_home.zip`, owned by `oracle:oinstall`, then verify:

```bash
stat -c '%n %s bytes' /u01/stage/LINUX.X64_193000_db_home.zip
sha256sum /u01/stage/LINUX.X64_193000_db_home.zip
```

Do not continue if either value differs. A small HTML file is not the installer; direct `curl` requests
to the Oracle 19c media URL can return a download page instead of the ZIP.

## A3. Extract the database home

Run as `oracle`:

```bash
umask 027
export ORACLE_BASE=/u01/app/oracle
export ORACLE_HOME=/u01/app/oracle/product/19.0.0/dbhome_1
export PATH="$ORACLE_HOME/bin:$PATH"
export CV_ASSUME_DISTID=OEL8

mkdir -p "$ORACLE_HOME"
unzip -tq /u01/stage/LINUX.X64_193000_db_home.zip
cd "$ORACLE_HOME"
unzip -q /u01/stage/LINUX.X64_193000_db_home.zip

test -x "$ORACLE_HOME/runInstaller" && echo 'runInstaller=OK'
```

## A4. Install Database 19c software only

Use a response file instead of one long installer command. Long terminal pastes can split Oracle
property arguments and cause `INS-35344` group errors.

```bash
RSP=/home/oracle/db_install_odi.rsp
cp "$ORACLE_HOME/install/response/db_install.rsp" "$RSP"

set_rsp() {
  sed -i "s|^${1}=.*|${1}=${2}|" "$RSP"
}

set_rsp oracle.install.option INSTALL_DB_SWONLY
set_rsp UNIX_GROUP_NAME oinstall
set_rsp INVENTORY_LOCATION /u01/app/oraInventory
set_rsp ORACLE_HOME "$ORACLE_HOME"
set_rsp ORACLE_BASE "$ORACLE_BASE"
set_rsp oracle.install.db.InstallEdition EE
set_rsp oracle.install.db.OSDBA_GROUP dba
set_rsp oracle.install.db.OSOPER_GROUP oper
set_rsp oracle.install.db.OSBACKUPDBA_GROUP backupdba
set_rsp oracle.install.db.OSDGDBA_GROUP dgdba
set_rsp oracle.install.db.OSKMDBA_GROUP kmdba
set_rsp oracle.install.db.OSRACDBA_GROUP racdba
set_rsp oracle.install.db.rootconfig.executeRootScript false

unset -f set_rsp
chmod 600 "$RSP"

./runInstaller -silent -waitforcompletion -responseFile "$RSP"
```

Continue only after the installer reports `Successfully Setup Software`. Then run as `root`, in this
order:

```bash
/u01/app/oraInventory/orainstRoot.sh
/u01/app/oracle/product/19.0.0/dbhome_1/root.sh
```

Verify as `oracle`:

```bash
export ORACLE_HOME=/u01/app/oracle/product/19.0.0/dbhome_1
export PATH="$ORACLE_HOME/bin:$PATH"

sqlplus -V
"$ORACLE_HOME/OPatch/opatch" version
"$ORACLE_HOME/OPatch/opatch" lspatches
```

The verified unpatched base reports SQL*Plus 19.3 and the factory 19.3 DB/OCW patches.

## A5. Create the listener

Run as `oracle`:

```bash
netca -silent -responseFile "$ORACLE_HOME/assistants/netca/netca.rsp"
lsnrctl status LISTENER
```

Before database creation, `The listener supports no services` is expected.

## A6. Create the CDB and PDB securely

Prepare a protected DBCA response file:

```bash
install -d -m 700 /home/oracle/.config/odi-lab

RSP=/home/oracle/.config/odi-lab/dbca_odilab.rsp
cp "$ORACLE_HOME/assistants/dbca/dbca.rsp" "$RSP"
chmod 600 "$RSP"
umask 077

set_rsp() {
  sed -i "s|^${1}=.*|${1}=${2}|" "$RSP"
}

set_rsp gdbName ODILAB
set_rsp sid ODILAB
set_rsp databaseConfigType SI
set_rsp templateName General_Purpose.dbc
set_rsp createAsContainerDatabase true
set_rsp numberOfPDBs 1
set_rsp pdbName ODIPDB
set_rsp useLocalUndoForPDBs true
set_rsp storageType FS
set_rsp datafileDestination /u02/oradata
set_rsp recoveryAreaDestination /u02/fra
set_rsp listeners LISTENER
set_rsp characterSet AL32UTF8
set_rsp nationalCharacterSet AL16UTF16
set_rsp sampleSchema false
set_rsp emConfiguration NONE
set_rsp automaticMemoryManagement false
set_rsp memoryPercentage ''
set_rsp totalMemory 4096
set_rsp initParams \
  'processes=1000,open_cursors=1600,session_cached_cursors=100,db_files=600'

DB_PASSWORD="OdiLab_7$(openssl rand -hex 10)"
set_rsp sysPassword "$DB_PASSWORD"
set_rsp systemPassword "$DB_PASSWORD"
set_rsp pdbAdminPassword "$DB_PASSWORD"

unset DB_PASSWORD
unset -f set_rsp

dbca -silent -createDatabase -responseFile "$RSP"
```

This lab uses one generated password for `SYS`, `SYSTEM`, and `PDBADMIN`. It remains only in the
mode-`600` response file until moved to a password manager. Do not print or commit the response file.

## A7. Apply repository-oriented database settings

Connect locally as `oracle`:

```bash
export ORACLE_SID=ODILAB
sqlplus / as sysdba
```

Run in SQL*Plus:

```sql
alter pluggable database ODIPDB save state;
alter system set shared_pool_size=512M scope=both;

show pdbs
show parameter db_block_size
show parameter db_files
show parameter open_cursors
show parameter processes
show parameter session_cached_cursors
show parameter sga_max_size
show parameter shared_pool_size

alter session set container=ODIPDB;

select comp_id, comp_name, version, status
from dba_registry
where comp_id in ('CATALOG', 'CATPROC', 'JAVAVM')
order by comp_id;

select parameter, value
from nls_database_parameters
where parameter in ('NLS_CHARACTERSET', 'NLS_NCHAR_CHARACTERSET')
order by parameter;

exit
```

Verified values:

| Check | Value |
|---|---|
| `ODIPDB` | `READ WRITE` |
| `JAVAVM`, `CATALOG`, `CATPROC` | `VALID` |
| Character sets | `AL32UTF8`, `AL16UTF16` |
| `db_block_size` | `8192` |
| `db_files` | `600` |
| `open_cursors` | `1600` |
| `processes` | `1000` |
| `session_cached_cursors` | `100` |
| `sga_max_size` | `3G` |
| `shared_pool_size` | `512M` |

## A8. Configure automatic startup with SELinux Enforcing

Enable the database in `/etc/oratab`:

```bash
sed -i 's|^ODILAB:\(.*\):N$|ODILAB:\1:Y|' /etc/oratab
grep '^ODILAB:' /etc/oratab
```

Create `/etc/init.d/dbora` as `root`:

```sh
#!/bin/sh
# chkconfig: 345 99 10
# description: Oracle Database ODILAB startup and shutdown

ORA_HOME=/u01/app/oracle/product/19.0.0/dbhome_1
ORA_OWNER=oracle

case "$1" in
  start)
    su - "$ORA_OWNER" -c "$ORA_HOME/bin/dbstart $ORA_HOME"
    ;;
  stop)
    su - "$ORA_OWNER" -c "$ORA_HOME/bin/dbshut $ORA_HOME"
    ;;
  restart)
    "$0" stop || exit $?
    "$0" start
    ;;
  *)
    echo "Usage: $0 {start|stop|restart}"
    exit 1
    ;;
esac
```

Register and test it:

```bash
chown root:root /etc/init.d/dbora
chmod 750 /etc/init.d/dbora
restorecon -v /etc/init.d/dbora

chkconfig --add dbora
chkconfig dbora on
systemctl daemon-reload

/etc/init.d/dbora stop
systemctl start dbora.service
systemctl is-enabled dbora.service
systemctl is-active dbora.service
systemctl status dbora.service --no-pager -l
```

Expected SELinux type: `initrc_exec_t`. Expected service state: `active (exited)`.

Do not execute `$ORACLE_HOME/bin/dbstart` directly from a custom native systemd service while Oracle
Home has the normal `/u01` `default_t` label. With SELinux Enforcing, systemd's `init_t` domain denies
that execution with status `203/EXEC`. Do not disable SELinux; use Oracle's documented init-script
pattern and restore its context.

## A9. Final database verification

After an actual VM reboot:

```bash
systemctl is-enabled dbora.service
systemctl is-active dbora.service

su - oracle -c \
'export ORACLE_HOME=/u01/app/oracle/product/19.0.0/dbhome_1
export ORACLE_SID=ODILAB
printf "show pdbs\nexit\n" | "$ORACLE_HOME/bin/sqlplus" -s / as sysdba'

su - oracle -c \
'export ORACLE_HOME=/u01/app/oracle/product/19.0.0/dbhome_1
export PATH="$ORACLE_HOME/bin:$PATH"
lsnrctl status LISTENER'
```

Completion requires `ODIPDB READ WRITE` and service `odipdb` with instance status `READY`.

## A10. Take a powered-off VirtualBox snapshot

Shut down the guest cleanly, wait for `Powered Off`, then run in Windows PowerShell:

```powershell
$vb = "C:\Program Files\Oracle\VirtualBox\VBoxManage.exe"

& $vb snapshot "OracleLinux8" take "01-db19c-base-odilab" `
  --description "OL8.10; DB19c 19.3 without RU; ODILAB/ODIPDB; before ODI"

& $vb snapshot "OracleLinux8" list
& $vb startvm "OracleLinux8" --type gui
```

# Part B — Install ODI 14.1.2 independently

## B1. Prerequisites

For a supported installation, verify the current Oracle certification matrix immediately before
installing. The documented minimums for Fusion Middleware 14.1.2 include:

- 64-bit JDK 17.0.12+ or 21.0.4+;
- Oracle Database 19c 19.14+ for RCU;
- Oracle JVM enabled;
- database character set `AL32UTF8`;
- the database parameters verified in Part A.

The current lab satisfies the JVM, character-set, and parameter requirements, but its deliberate 19.3
base-release deviation does not satisfy the RCU version minimum.

## B2. Install a dedicated JDK

The verified lab uses the public script-friendly Oracle JDK 21 archive:

```text
File:    jdk-21_linux-x64_bin.tar.gz
Version: 21.0.12.1
Size:    198949838 bytes
SHA256:  12f870b21301b42292558a3f872ce543affa2b86cb6458591c78388c41ddb111
```

Oracle's `/latest/` URL is mutable. On a later date, identify the extracted version and record the new
size and SHA-256 rather than assuming the values above still apply.

Download and verify as `oracle`:

```bash
cd /u01/stage

curl --fail --location --retry 3 --continue-at - \
  --output jdk-21_linux-x64_bin.tar.gz \
  https://download.oracle.com/java/21/latest/jdk-21_linux-x64_bin.tar.gz

curl --fail --location \
  --output jdk-21_linux-x64_bin.tar.gz.sha256 \
  https://download.oracle.com/java/21/latest/jdk-21_linux-x64_bin.tar.gz.sha256

sha256sum jdk-21_linux-x64_bin.tar.gz
cat jdk-21_linux-x64_bin.tar.gz.sha256
tar -tzf jdk-21_linux-x64_bin.tar.gz | head -n 5
```

Install outside every Oracle Home:

```bash
JAVA_BASE=/u01/app/oracle/product/java
install -d -m 775 "$JAVA_BASE"
tar -xzf /u01/stage/jdk-21_linux-x64_bin.tar.gz -C "$JAVA_BASE"
ln -s jdk-21.0.12.1 "$JAVA_BASE/current"

export JAVA_HOME="$JAVA_BASE/current"
export PATH="$JAVA_HOME/bin:$PATH"

java -version
javac -version
```

Persist only neutral paths in `/home/oracle/.bash_profile`:

```bash
export JAVA_HOME=/u01/app/oracle/product/java/current
export DB_HOME=/u01/app/oracle/product/19.0.0/dbhome_1
export ORACLE_SID=ODILAB
export PATH="$JAVA_HOME/bin:$DB_HOME/bin:$PATH"
```

Do not set a global `ORACLE_HOME`: the database and FMW/ODI use different homes. For database tools,
use an explicit environment assignment:

```bash
ORACLE_HOME="$DB_HOME" "$DB_HOME/bin/sqlplus" -V
```

## B3. Obtain and verify official ODI media

The verified Oracle eDelivery package is:

```text
File:    V1045400-01.zip
Product: Oracle Fusion Middleware 14c Data Integrator 14.1.2.0.0
Size:    2802140309 bytes
SHA256:  2d4b2f7a00a4a5f50231fc105363bcff4c9c77550dfa1b8f7e9f6fc69d2696de
```

Oracle Software Delivery Cloud can generate a `wget.sh` that uses a temporary access token. Inspect
generated scripts before execution. The script observed in this lab printed `$ACCESS_TOKEN`; the safe
copy at `/u01/stage/wget-odi-safe.sh` changed that line to a blank `echo`, set `LOGDIR` and `OUTPUT_DIR`
to `/u01/stage`, and used Bash explicitly. Never print, log, commit, or paste the token into chat.

The ZIP contains one installer:

```text
File:    fmw_14.1.2.0.0_odi.jar
Size:    2802486715 bytes
SHA256:  4cdca4f11d2e8bfd46faffe6e0853a2d64f43184ef3979e31fd148ce11d985e9
```

Acquire media only through Oracle Technical Resources/OTN or Oracle Software Delivery Cloud under the
applicable license. Do not bypass Oracle registration or use unofficial mirrors.

Verify and extract as `oracle`:

```bash
cd /u01/stage
unzip -tq V1045400-01.zip
sha256sum V1045400-01.zip

MEDIA_DIR=/u01/stage/odi-14.1.2-media
install -d -m 750 "$MEDIA_DIR"
unzip -q V1045400-01.zip -d "$MEDIA_DIR"

INSTALLER="$MEDIA_DIR/fmw_14.1.2.0.0_odi.jar"
stat -c '%n %s bytes' "$INSTALLER"
sha256sum "$INSTALLER"
"$JAVA_HOME/bin/jarsigner" -verify "$INSTALLER"
```

The verified result was `jar verified`. Warnings about POSIX/symlink metadata in the self-extracting
installer did not invalidate its signature.

## B4. Install the Enterprise feature set

Install the required Oracle Linux 8 packages as `root`:

```bash
dnf install -y \
  binutils gcc gcc-c++ glibc glibc-devel \
  libaio libaio-devel libgcc libstdc++ libstdc++-devel \
  libnsl sysstat motif motif-devel openssl-libs \
  make xorg-x11-utils ksh libcap
```

Create the parent and temporary directories without pre-creating the FMW home:

```bash
install -d -o oracle -g oinstall -m 775 \
  /u01/app/oracle/product/fmw/14.1.2 \
  /u01/stage/tmp-odi

test ! -e /u01/app/oracle/product/fmw/14.1.2/odi \
  && echo 'FMW_HOME=EMPTY'
```

Create `/home/oracle/odi14c_install.rsp`, mode `600`:

```ini
[ENGINE]
Response File Version=1.0.0.0.0

[GENERIC]
ORACLE_HOME=/u01/app/oracle/product/fmw/14.1.2/odi
INSTALL_TYPE=Enterprise Installation
DECLINE_AUTO_UPDATES=true
COLLECTOR_SUPPORTHUB_URL=
```

Stop the database temporarily to free memory, then run as `oracle`:

```bash
export JAVA_HOME=/u01/app/oracle/product/java/current
export TMPDIR=/u01/stage/tmp-odi

INSTALLER=/u01/stage/odi-14.1.2-media/fmw_14.1.2.0.0_odi.jar
RSP=/home/oracle/odi14c_install.rsp

"$JAVA_HOME/bin/java" \
  -Djava.io.tmpdir="$TMPDIR" \
  -jar "$INSTALLER" \
  -silent \
  -responseFile "$RSP"
```

The verified installer accepted Oracle Linux 8.10 and JDK 21.0.12.1 and reported:

```text
The installation of Oracle Data Integrator 14.1.2.0.0 completed successfully.
```

This Enterprise installer laid down the required Infrastructure and ODI in one Oracle Home; separate
Infrastructure media was not needed in this verified flow.

## B5. Verify ODI Studio, RCU, and SDK clients

Restart the database, then run as `oracle`:

```bash
export FMW_HOME=/u01/app/oracle/product/fmw/14.1.2/odi

test -x "$FMW_HOME/odi/studio/odi.sh" && echo 'ODI_STUDIO=OK'
test -x "$FMW_HOME/oracle_common/bin/rcu" && echo 'RCU=OK'
"$FMW_HOME/OPatch/opatch" version
```

Verified ODI home size: 4.1 GB. Verified OPatch version: 13.9.4.2.17.

Materialized clients under `$FMW_HOME/odi/modules/clients`:

| File | Size | SHA-256 |
|---|---:|---|
| `oracle.odi.common.clientLib.jar` | 156029282 | `a1733d9d3a0d86feaa8b87b10b66c866e01fdaab182343c0c7c13aa9af79da9b` |
| `oracle.odi.tp.clientLib.jar` | 6176282 | `ba70a29e4fcd8664df2aeae38c7b2575ac0f460a9c5a9ed159d03c361260b6cc` |
| `oracle.odi.sdk.clientLib.jar` | 85250449 | `7e4d76dda9866f9684df2050cd54287e73402182c18e12b8cbe129571543bf1c` |

These files are licensed Oracle media. Keep them outside Git.

## B6. Create the ODI repository with RCU

Launch RCU from the graphical `kuba` session as the Oracle installation owner:

```bash
xhost +SI:localuser:oracle

sudo -u oracle -H env \
  DISPLAY="$DISPLAY" \
  XAUTHORITY="${XAUTHORITY:-}" \
  JAVA_HOME=/u01/app/oracle/product/java/current \
  /u01/app/oracle/product/fmw/14.1.2/odi/oracle_common/bin/rcu
```

Verified selections:

```text
Operation:          System Load and Product Load
Database Type:      Oracle EBR Database
Host:               odi14c-lab
Port:               1521
Service:            odipdb
DB user/role:       SYS / SYSDBA
Component:          Oracle Data Integrator > Master and Work Repository
Master schema:      CBK_ODI14C_MASTER
Work schema:        CBK_ODI14C_WORK
Work Repository:    DEV_WORKREP
```

Choose project-specific Master and Work schemas and a Development Work Repository. Store schema,
`SUPERVISOR`, and Work Repository passwords in a password manager; do not put them in the runbook.
RCU reported Success in this lab even though base 19.3 is below the documented 19.14 minimum. This
successful experiment does not make the combination certified.

## B7. Launch ODI Studio and connect

Studio runs as `oracle`, while the desktop belongs to `kuba`. The convenience launcher
`/home/kuba/bin/odi-studio` contains:

```bash
#!/usr/bin/env bash
set -euo pipefail

ODI_HOME=/u01/app/oracle/product/fmw/14.1.2/odi
ODI_JAVA_HOME=/u01/app/oracle/product/java/current

if [[ -z "${DISPLAY:-}" ]]; then
  echo "Run from a terminal in the graphical VM session."
  exit 1
fi

cleanup() {
  xhost -SI:localuser:oracle >/dev/null 2>&1 || true
}

trap cleanup EXIT INT TERM
xhost +SI:localuser:oracle >/dev/null

sudo -u oracle -H env \
  DISPLAY="$DISPLAY" \
  XAUTHORITY="${XAUTHORITY:-}" \
  JAVA_HOME="$ODI_JAVA_HOME" \
  FMW_HOME="$ODI_HOME" \
  "$ODI_HOME/odi/studio/odi.sh"
```

Set mode `750` and run with `~/bin/odi-studio`. Use ODI's password-protected wallet. Connect with
`SUPERVISOR`, the actual `<PREFIX>_ODI_REPO` database user, and the service containing the schemas.

ODI Studio heap is configured in `$FMW_HOME/odi/studio/bin/odi.conf`. The current lab uses:

```text
AddVMOption -Xms2048m
AddVMOption -Xmx8192m
AddVMOption -Xss4m
```

With a 16 GB guest, close SQL Developer during a large Smart Import and monitor swap. Reduce `-Xmx` to
6144 MB if swap grows or the desktop becomes unresponsive.

## B8. Install SQL Developer for repository inspection

Verified SQL Developer package:

```text
File:    sqldeveloper-26.2.0-186.2220.noarch.rpm
Size:    420134798 bytes
MD5:     2c50ead6d619485cd3ce2e04ab8ec33c
SHA1:    80dfe33e032c043fc890e86366d62d6a0552f2b2
SHA256:  f2366b746f5a42b431448cb07606bde1c47c28398c8d7e74f7665e675ab3fdee
```

Download from Oracle, verify the published MD5/SHA1 and RPM digests, then install with `dnf`. SQL
Developer 26.2 launched successfully on the dedicated JDK. For user `kuba`, configure:

```text
~/.sqldeveloper/26.2.0/product.conf
SetJavaHome /u01/app/oracle/product/java/current
```

Do not save the `SYS` password unless required. Ensure `~/.sqldeveloper` is not world-readable.

## B9. Import a project patch

Create a shared import drop owned by the SSH user and readable by `oracle`:

```bash
install -d -o odi-dev -g oinstall -m 2770 /u01/stage/odi-import
```

Copy from WSL directly into that directory with SCP, then select the file in ODI Studio. The verified
lab imported:

```text
/u01/stage/odi-import/INITIAL_REPO_DEVTS.zip
```

The user reported successful Smart Import completion. The file size, SHA-256, and imported object count
were not captured and should be recorded if the artifact is reused.

## B10. SDK handoff and remaining standalone agent

The verified private WSL cache is:

```text
/root/.local/share/oracle/odi/14.1.2/lib
```

It contains the three materialized ODI clients listed in B5 and:

| File | Size | SHA-256 |
|---|---:|---|
| `ojdbc11.jar` | 7196593 bytes | `dc1a3d0fa7c75599e69b310dc7e5226e771c1cf77e060cc0a0e8c19f7e1ef1c5` |

Keep these four libraries outside Git and build the version-specific Java adapter against this cache.
The standalone explorer additionally declares `org.json:json:20240303`; ODI's client JARs reference
`org.json.JSONTokener` but do not materialize that class.

The standalone agent is still pending. For execution tests, create a physical/logical
`OracleDIAgent1`, configure the `Oracle Data Integrator - Standalone Agent` domain outside Oracle Home,
and start it without Node Manager. The SDK lineage extractor itself does not require an agent.

## B11. Verified result

```text
FMW/ODI home: /u01/app/oracle/product/fmw/14.1.2/odi
Install type: Enterprise Installation
Database:     odi14c-lab:1521/odipdb
Repository:   ODI Master and Work Repository in ODIPDB
```

Verified outcomes:

- ODI 14.1.2 Enterprise installed successfully;
- Studio and RCU executable;
- RCU repository created and Studio login working;
- initial project patch imported;
- three materialized client libraries inventoried and hashed;
- SQL Developer available for manual repository inspection.

# Part C — Operate and preserve the VirtualBox lab

## C1. Verified VM profile

The tuned profile for the observed Windows host is:

```text
RAM:                  16384 MB
vCPU:                 10
CPU execution cap:    100%
Graphics controller:  VMSVGA
Video memory:         128 MB
3D acceleration:      disabled
Paravirtualization:   KVM
```

The observed host has 24 physical cores / 32 logical processors and 64 GB RAM. Do not generalize this
allocation to smaller hosts.

## C2. Configure VirtualBox NAT forwarding

Keep adapter 1 in NAT mode. In VirtualBox, open **Settings → Network → Adapter 1 → Advanced → Port
Forwarding** and add all three rules:

| Name | Protocol | Host IP | Host port | Guest IP | Guest port |
|---|---|---|---:|---|---:|
| `odi-ssh` | TCP | `172.28.48.1` | 2222 | empty | 22 |
| `odi-db` | TCP | `172.28.48.1` | 15210 | empty | 1521 |
| `odi-db-native` | TCP | `172.28.48.1` | 1521 | empty | 1521 |

The host IP is the current Windows-side WSL adapter address and can change after WSL networking
restarts. Update all three rules together if it changes. `odi-db-native` is required because the Work
Repository resource stored in Master uses `odi14c-lab:1521`; the external application maps that name
to the Windows WSL adapter. Allow the listener on the guest firewall and verify:

```bash
sudo firewall-cmd --permanent --add-port=1521/tcp
sudo firewall-cmd --reload
sudo firewall-cmd --query-port=1521/tcp
sudo ss -lntp | grep ':1521'
```

From WSL, use `172.28.48.1:2222` for SSH and this JDBC URL for the lab database:

```text
jdbc:oracle:thin:@//172.28.48.1:15210/odipdb
```

For a backend running outside the VM, keep the machine-local hostname mapping outside Git:

```text
172.28.48.1 odi14c-lab
```

## C3. Repair Guest Additions after a UEK update

Guest Additions 7.0.18 initially failed because headers for the running UEK were absent. Repair with:

```bash
KERNEL_VERSION=$(uname -r)

sudo dnf install -y \
  "kernel-uek-devel-$KERNEL_VERSION" \
  gcc make perl elfutils-libelf-devel

sudo /sbin/rcvboxadd setup
sudo /sbin/rcvboxadd status-kernel
```

Remove `nomodeset` only after the modules build successfully:

```bash
sudo grubby --update-kernel=ALL --remove-args="nomodeset"
sudo grubby --info=DEFAULT | grep -E '^(kernel|args)='
```

After reboot, the verified graphics driver was `vmwgfx`, `vboxguest` 7.0.18 was loaded, and no new
`soft lockup` appeared in the captured kernel log. `vboxsf` is expected to remain unloaded when Shared
Folders are not used.

## C4. Clean shutdown

Close ODI Studio and SQL Developer. The convenience script `/home/kuba/bin/odi-lab-shutdown` should:

1. refuse shutdown while either GUI is running;
2. stop `dbora.service`;
3. verify the DB and listener processes stopped;
4. sync and power off the guest.

Its exact contents were supplied during setup, but its final file contents and checksum were not read
back. Verify it before treating it as automation. A manual safe shutdown is:

```bash
sudo systemctl stop dbora.service
systemctl is-active dbora.service
sudo shutdown -h now
```

## C5. Snapshot checkpoints

Always take snapshots while the VM is `Powered Off`. Requested checkpoints:

```text
01-db19c-base-odilab
02-odi14c-binaries-no-repo
03-odi14c-ready-initial-repo
```

The conversation did not capture `VBoxManage snapshot list` output, so confirm these names in the
VirtualBox Snapshot view. Snapshot 03 should describe the complete database, ODI repository, imported
project patch, JDK, SQL Developer, repaired Guest Additions, and 16 GB/10 vCPU profile.

A VirtualBox snapshot is a rollback point, not an independent backup. For migration to another PC or
macOS host, cleanly power off the VM and additionally export an OVA or copy the complete VM directory.

## C6. Management Pack licensing guardrail

The observed EE default was:

```text
control_management_pack_access=DIAGNOSTIC+TUNING
```

This setting enables functionality but does not grant a license. Real-Time SQL Monitoring is a Tuning
Pack feature and Tuning Pack also requires Diagnostics Pack. The recommendation was to set the CDB
parameter to `NONE` unless the applicable agreement explicitly licenses both packs. Completion of that
change was not confirmed.

## Official references

- [Oracle Database 19c installation guide for Linux](https://docs.oracle.com/en/database/oracle/oracle-database/19/ladbi/)
- [Automating Oracle Database startup and shutdown](https://docs.oracle.com/en/database/oracle/oracle-database/19/unxar/stopping-and-starting-oracle-software.html)
- [Fusion Middleware 14.1.2 system requirements](https://docs.oracle.com/en/middleware/fusion-middleware/14.1.2/sysrs/system-requirements-and-specifications.html)
- [Installing and Configuring Oracle Data Integrator](https://docs.oracle.com/en/middleware/fusion-middleware/14.1.2/oding/)
- [Oracle Data Integrator downloads](https://www.oracle.com/middleware/technologies/data-integrator-downloads.html)
- [Oracle JDK downloads](https://www.oracle.com/java/technologies/downloads/)
