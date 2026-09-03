#!/usr/bin/env bash

set -euo pipefail

VM_HOST="${VM_HOST:-127.0.0.1}"
VM_PORT="${VM_PORT:-2222}"
VM_USER="${VM_USER:-odi-dev}"
SSH_KEY="${SSH_KEY:-${HOME}/.ssh/openmetadata_odi14c_ed25519}"
REMOTE_DIR="${REMOTE_DIR:-/home/odi-dev/odi-sdk-transfer}"
ODI_BASE="${ODI_BASE:-${HOME}/.local/share/oracle/odi/14.1.2}"
ODI_LIB="${ODI_BASE}/lib"

FILES=(
  oracle.odi.common.clientLib.jar
  oracle.odi.tp.clientLib.jar
  oracle.odi.sdk.clientLib.jar
  ojdbc11.jar
)

if [[ ! -r "${SSH_KEY}" ]]; then
  printf 'Brak czytelnego klucza SSH: %s\n' "${SSH_KEY}" >&2
  exit 1
fi

for command_name in scp mktemp; do
  if ! command -v "${command_name}" >/dev/null 2>&1; then
    printf 'Brak wymaganego polecenia: %s\n' "${command_name}" >&2
    exit 1
  fi
done

mkdir -p "${ODI_LIB}"
chmod 700 "${ODI_BASE}" "${ODI_LIB}"

for file_name in "${FILES[@]}" checksums.sha256; do
  destination="${ODI_LIB}/${file_name}"
  if [[ "${file_name}" == checksums.sha256 ]]; then
    destination="${ODI_BASE}/${file_name}"
  fi
  if [[ -e "${destination}" ]]; then
    printf 'STOP: plik już istnieje: %s\n' "${destination}" >&2
    exit 1
  fi
done

transfer_dir="$(mktemp -d "${TMPDIR:-/tmp}/odi-sdk-transfer.XXXXXX")"
trap 'rm -rf "${transfer_dir}"' EXIT

scp_options=(
  -i "${SSH_KEY}"
  -P "${VM_PORT}"
  -o BatchMode=yes
  -o ConnectTimeout=10
  -o StrictHostKeyChecking=accept-new
)

printf 'Pobieranie ODI SDK z %s@%s, port %s, katalog %s\n' \
  "${VM_USER}" "${VM_HOST}" "${VM_PORT}" "${REMOTE_DIR}"

for file_name in "${FILES[@]}" checksums.sha256; do
  printf '  - %s\n' "${file_name}"
  scp "${scp_options[@]}" \
    "${VM_USER}@${VM_HOST}:${REMOTE_DIR}/${file_name}" \
    "${transfer_dir}/${file_name}"
done

if command -v sha256sum >/dev/null 2>&1; then
  (cd "${transfer_dir}" && sha256sum -c checksums.sha256)
elif command -v shasum >/dev/null 2>&1; then
  (cd "${transfer_dir}" && shasum -a 256 -c checksums.sha256)
else
  printf 'Brak sha256sum lub shasum do weryfikacji plików.\n' >&2
  exit 1
fi

for file_name in "${FILES[@]}"; do
  mv "${transfer_dir}/${file_name}" "${ODI_LIB}/${file_name}"
  chmod 600 "${ODI_LIB}/${file_name}"
done
mv "${transfer_dir}/checksums.sha256" "${ODI_BASE}/checksums.sha256"
chmod 600 "${ODI_BASE}/checksums.sha256"

printf '\nPobrane i zweryfikowane pliki:\n'
for file_name in "${FILES[@]}"; do
  file_path="${ODI_LIB}/${file_name}"
  byte_count="$(wc -c < "${file_path}" | tr -d '[:space:]')"
  printf '  %s (%s bytes)\n' "${file_path}" "${byte_count}"
done
