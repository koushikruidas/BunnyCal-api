#!/usr/bin/env bash
set -euo pipefail

# ---------------------------------------------------------------------------
# Nightly logical backup of the BunnyCal production database.
#
# This is the PORTABLE backup layer. The managed provider's automated backups
# and PITR are the low-RPO layer and cover disk failure, bad migrations and
# accidental deletes. They do NOT cover losing the provider account itself
# (provider backups are tied to the instance, so deleting it deletes them).
# That is what this script is for, which is why the dump must land with a
# DIFFERENT vendor than the one hosting the database.
#
# A `pg_dump -Fc` also restores into any PG16+ anywhere — another cloud, another
# managed provider, or a laptop — so it doubles as the provider-migration and
# major-version-upgrade mechanism.
#
# Flow:  pg_dump -Fc  ->  age encrypt  ->  rclone upload  ->  prune  ->  ping
#
# The healthcheck is pinged ONLY after every step has succeeded. Any failure
# leaves the switch un-pinged, and the dead-man's-switch emails you. `set -e`
# plus an explicit failure trap means a partial backup can never look healthy.
#
# Usage:   scripts/backup/pg-dump-nightly.sh
# Config:  read from environment (see .env.example "Backups" section)
# Invoked: by bunnycal-backup.service, on the bunnycal-backup.timer schedule
# ---------------------------------------------------------------------------

readonly REQUIRED_VARS=(
  BACKUP_DATABASE_URL
  BACKUP_AGE_PUBLIC_KEY
  BACKUP_RCLONE_REMOTE
  BACKUP_LOCAL_DIR
)

: "${BACKUP_RETAIN_DAYS:=14}"
: "${BACKUP_HEALTHCHECK_URL:=}"

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
die() { log "ERROR: $*" >&2; exit 1; }

# Report which step failed. Without this, a mid-pipeline failure is just a bare
# non-zero exit in journalctl with no indication of where it broke.
readonly STAGE_FILE="$(mktemp)"
echo "startup" >"${STAGE_FILE}"
stage() { echo "$*" >"${STAGE_FILE}"; log "==> $*"; }
# PARTIAL is assigned later; the trap must not fail on the unset case.
PARTIAL=""
on_exit() {
  local rc=$?
  if [[ ${rc} -ne 0 ]]; then
    log "FAILED during stage: $(cat "${STAGE_FILE}") (exit ${rc})"
    # Remove the incomplete dump immediately. Leaving it behind would let
    # repeated same-day failures accumulate half-written files, and a stray
    # .partial is exactly the kind of thing someone reaches for in an outage.
    if [[ -n "${PARTIAL}" && -e "${PARTIAL}" ]]; then
      rm -f "${PARTIAL}"
      log "removed incomplete dump: ${PARTIAL}"
    fi
    log "Healthcheck NOT pinged — the dead-man's-switch will alert."
  fi
  rm -f "${STAGE_FILE}"
  exit "${rc}"
}
trap on_exit EXIT

# --- Preflight -------------------------------------------------------------
stage "preflight"

for var in "${REQUIRED_VARS[@]}"; do
  [[ -n "${!var:-}" ]] || die "${var} is not set (see .env.example)"
done

for bin in pg_dump age rclone; do
  command -v "${bin}" >/dev/null 2>&1 || die "required binary not found: ${bin}"
done

# Reject a plaintext connection to a REMOTE database. This dump contains every
# user's PII, so an unverified TLS session over a network is not acceptable.
#
# A loopback connection is exempt: Postgres now runs on this same host (see
# DEPLOYMENT.md §0), the traffic never reaches a network interface, and the
# local server has no certificate for verify-full to check. If the database is
# ever moved off-box, the host stops matching localhost here and TLS becomes
# mandatory again automatically.
case "${BACKUP_DATABASE_URL}" in
  *@localhost:*|*@localhost/*|*@127.0.0.1:*|*@127.0.0.1/*|*"@[::1]":*)
    log "NOTE: loopback database connection — TLS not required."
    ;;
  *sslmode=verify-full*|*sslmode=verify-ca*) ;;
  *) die "BACKUP_DATABASE_URL is remote and must use sslmode=verify-full (or verify-ca)" ;;
esac

mkdir -p "${BACKUP_LOCAL_DIR}"

readonly STAMP="$(date -u +%Y-%m-%d)"
readonly BASENAME="bunnycal-${STAMP}.dump.age"
readonly TARGET="${BACKUP_LOCAL_DIR}/${BASENAME}"
# Write to a .partial file so an interrupted run can never leave a truncated
# dump sitting where the next step (or a human) would treat it as complete.
# Not readonly: the exit trap reads it, so it is declared before the dump stage.
PARTIAL="${TARGET}.partial"
rm -f "${PARTIAL}"

# --- Dump + encrypt --------------------------------------------------------
# -Fc  custom format: compressed, parallel-restorable, selectively restorable
# -Z6  balance CPU against size; avatars are BYTEA and compress poorly anyway
#
# PIPESTATUS is checked explicitly: `set -o pipefail` catches a pg_dump failure,
# but being explicit makes the failure attributable in the log.
stage "pg_dump + age encrypt -> ${PARTIAL}"
set +e
pg_dump -Fc -Z6 --no-owner --no-privileges "${BACKUP_DATABASE_URL}" \
  | age -r "${BACKUP_AGE_PUBLIC_KEY}" >"${PARTIAL}"
readonly PIPE_STATUS=("${PIPESTATUS[@]}")
set -e
[[ ${PIPE_STATUS[0]} -eq 0 ]] || die "pg_dump failed (exit ${PIPE_STATUS[0]})"
[[ ${PIPE_STATUS[1]} -eq 0 ]] || die "age encryption failed (exit ${PIPE_STATUS[1]})"

# A dump that is implausibly small usually means pg_dump wrote an error payload
# or the database came back empty. Catch it here rather than discovering it
# during a restore, six weeks later.
readonly SIZE_BYTES="$(wc -c <"${PARTIAL}" | tr -d '[:space:]')"
[[ "${SIZE_BYTES}" -gt 4096 ]] \
  || die "dump is only ${SIZE_BYTES} bytes — refusing to publish a suspect backup"

mv "${PARTIAL}" "${TARGET}"
log "wrote ${TARGET} (${SIZE_BYTES} bytes)"

# --- Verify the encryption round-trips -------------------------------------
# An unreadable backup is worse than no backup, because it looks like safety.
# If the private key is available, prove the file actually decrypts and that
# pg_restore recognises it as a valid archive. This catches a wrong/rotated
# BACKUP_AGE_PUBLIC_KEY on the night it happens rather than during an outage.
if [[ -n "${BACKUP_AGE_PRIVATE_KEY_FILE:-}" && -r "${BACKUP_AGE_PRIVATE_KEY_FILE}" ]]; then
  stage "verify decrypt + archive readability"
  age -d -i "${BACKUP_AGE_PRIVATE_KEY_FILE}" "${TARGET}" \
    | pg_restore --list >/dev/null \
    || die "backup failed verification: could not decrypt or read the archive"
  log "verified: decrypts cleanly and pg_restore can read the table of contents"
else
  log "NOTE: BACKUP_AGE_PRIVATE_KEY_FILE unset — skipping decrypt verification."
  log "      Set it to catch a wrong age key on the night it breaks."
fi

# --- Upload off-site -------------------------------------------------------
stage "upload -> ${BACKUP_RCLONE_REMOTE}"
rclone copy --no-traverse "${TARGET}" "${BACKUP_RCLONE_REMOTE}/" \
  || die "off-site upload failed"

# Confirm the object actually landed. rclone exiting 0 without the file present
# would otherwise leave us believing an absent backup exists.
rclone lsf "${BACKUP_RCLONE_REMOTE}/${BASENAME}" >/dev/null \
  || die "upload reported success but ${BASENAME} is not present at the remote"
log "confirmed present off-site: ${BACKUP_RCLONE_REMOTE}/${BASENAME}"

# --- Prune local copies ----------------------------------------------------
# Local pruning only. Remote retention is configured at the storage vendor, on
# purpose: a bug here must never be able to delete the off-site copies, and the
# upload credential should not have delete permission at all.
#
# Dumps taken on the 1st of the month are kept indefinitely as monthly archives.
stage "prune local dumps older than ${BACKUP_RETAIN_DAYS} days"
while IFS= read -r -d '' old; do
  if [[ "$(basename "${old}")" =~ ^bunnycal-[0-9]{4}-[0-9]{2}-01\.dump\.age$ ]]; then
    log "keeping monthly archive: $(basename "${old}")"
    continue
  fi
  log "pruning $(basename "${old}")"
  rm -f "${old}"
done < <(find "${BACKUP_LOCAL_DIR}" -maxdepth 1 -name 'bunnycal-*.dump.age' \
           -type f -mtime "+${BACKUP_RETAIN_DAYS}" -print0)

# Clean up any partial files left behind by a previously killed run.
find "${BACKUP_LOCAL_DIR}" -maxdepth 1 -name '*.dump.age.partial' -type f -mtime +1 -delete

# --- Signal success --------------------------------------------------------
# Reached only if every step above succeeded.
stage "ping healthcheck"
if [[ -n "${BACKUP_HEALTHCHECK_URL}" ]]; then
  curl -fsS --retry 3 --max-time 30 "${BACKUP_HEALTHCHECK_URL}" >/dev/null \
    && log "healthcheck pinged" \
    || log "WARNING: backup succeeded but healthcheck ping failed"
else
  log "NOTE: BACKUP_HEALTHCHECK_URL unset — no dead-man's-switch configured."
fi

log "backup completed successfully: ${BASENAME} (${SIZE_BYTES} bytes)"
