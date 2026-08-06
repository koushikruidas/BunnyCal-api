#!/usr/bin/env bash
set -euo pipefail

# ---------------------------------------------------------------------------
# Point-in-time restore of the BunnyCal database into a SCRATCH cluster.
#
# This script restores to a NEW directory on a SPARE PORT. It never touches the
# live cluster — by design, and enforced below. Restoring in place during an
# incident, before you have confirmed the recovered data is actually what you
# want, destroys your ability to try a different target time.
#
# Two uses:
#   1. The monthly drill. A backup that has never been restored is hope, not a
#      backup. Run it, verify, note the wall-clock time, throw the result away.
#   2. A real incident. Restore here, verify, and only then promote — see
#      docs/runbooks/disaster-recovery.md for the promotion steps.
#
# Usage:
#   scripts/restore/pitr-restore.sh '2026-08-05 14:30:00+00'
#   scripts/restore/pitr-restore.sh latest
#   RESTORE_PORT=5433 scripts/restore/pitr-restore.sh '2026-08-05 14:30:00+00'
#
# Run as postgres:  sudo -u postgres scripts/restore/pitr-restore.sh ...
# ---------------------------------------------------------------------------

: "${STANZA:=bunnycal}"
: "${RESTORE_PORT:=5433}"
: "${RESTORE_DIR:=/var/lib/postgresql/restore-scratch}"
: "${PG_BIN:=}"

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
die() { log "ERROR: $*" >&2; exit 1; }

readonly STAGE_FILE="$(mktemp)"
echo "startup" >"${STAGE_FILE}"
stage() { echo "$*" >"${STAGE_FILE}"; log "==> $*"; }
on_exit() {
  local rc=$?
  [[ ${rc} -ne 0 ]] && log "FAILED during stage: $(cat "${STAGE_FILE}") (exit ${rc})"
  rm -f "${STAGE_FILE}"
  exit "${rc}"
}
trap on_exit EXIT

# --- Arguments -------------------------------------------------------------
[[ $# -eq 1 ]] || die "usage: $0 <'YYYY-MM-DD HH:MM:SS+00' | latest>"
readonly TARGET="$1"

# --- Preflight -------------------------------------------------------------
stage "preflight"

[[ "$(id -un)" == "postgres" ]] \
  || die "run as the postgres user: sudo -u postgres $0 '${TARGET}'"

command -v pgbackrest >/dev/null 2>&1 || die "pgbackrest not found"

# Locate the server binaries. pg_ctl is not on PATH in a default Debian/Ubuntu
# install — the packaged wrappers are, but they target the live cluster.
#
# Pick the HIGHEST installed major version. A plain glob would sort
# lexicographically, which puts 9.6 after 17 and would silently restore with
# the wrong server binaries.
if [[ -z "${PG_BIN}" ]]; then
  for candidate in $(ls -1 /usr/lib/postgresql 2>/dev/null | sort -rV); do
    if [[ -x "/usr/lib/postgresql/${candidate}/bin/pg_ctl" ]]; then
      PG_BIN="/usr/lib/postgresql/${candidate}/bin"
      break
    fi
  done
fi
[[ -n "${PG_BIN}" && -x "${PG_BIN}/pg_ctl" ]] \
  || die "could not locate pg_ctl under /usr/lib/postgresql — set PG_BIN explicitly"
log "using binaries from ${PG_BIN}"

# Queried once here and reused by the safety guard below. Empty means no live
# cluster is reachable, which is normal during a bare-metal recovery.
LIVE_DATA_DIR="$(psql -Atc 'SHOW data_directory' 2>/dev/null || true)"

# A physical backup can only be read by its own major version. Restoring a PG17
# backup with PG16 binaries fails with a confusing control-file error; catching
# it here names the actual problem.
if [[ -n "${LIVE_DATA_DIR}" ]]; then
  live_major="$(psql -Atc 'SHOW server_version_num' 2>/dev/null | cut -c1-2 || true)"
  bin_major="$("${PG_BIN}/pg_ctl" --version | grep -oE '[0-9]+' | head -1 || true)"
  if [[ -n "${live_major}" && -n "${bin_major}" && "${live_major}" != "${bin_major}" ]]; then
    log "WARNING: live cluster is PG${live_major} but binaries are PG${bin_major}."
    log "         A physical restore requires the backup's own major version."
  fi
fi

# --- Guard: never restore over the live cluster ----------------------------
# The whole safety model rests on this. pgBackRest --delta against the live
# data directory would overwrite the running database in place.
stage "verify the restore target is not the live cluster"

if [[ -n "${LIVE_DATA_DIR}" ]]; then
  # Assigned separately from `readonly` so a readlink failure is not masked by
  # the declaration's own exit status — this guard is the reason the script is
  # safe to run during an incident, so it must not fail open.
  RESOLVED_RESTORE="$(readlink -f "${RESTORE_DIR}" 2>/dev/null || echo "${RESTORE_DIR}")"
  RESOLVED_LIVE="$(readlink -f "${LIVE_DATA_DIR}")" \
    || die "could not resolve the live data directory ${LIVE_DATA_DIR}"
  readonly RESOLVED_RESTORE RESOLVED_LIVE
  [[ "${RESOLVED_RESTORE}" != "${RESOLVED_LIVE}" ]] \
    || die "RESTORE_DIR is the LIVE data directory (${LIVE_DATA_DIR}) — refusing"
  log "live cluster is at ${LIVE_DATA_DIR}; restoring elsewhere"
else
  log "NOTE: no live cluster reachable — continuing (this may be a bare-metal recovery)"
fi

readonly LIVE_PORT="$(psql -Atc 'SHOW port' 2>/dev/null || echo '')"
if [[ -n "${LIVE_PORT}" && "${LIVE_PORT}" == "${RESTORE_PORT}" ]]; then
  die "RESTORE_PORT ${RESTORE_PORT} is the live cluster's port — pick another"
fi

# --- Prepare the scratch directory -----------------------------------------
stage "prepare ${RESTORE_DIR}"

if [[ -d "${RESTORE_DIR}" ]] && [[ -n "$(ls -A "${RESTORE_DIR}" 2>/dev/null)" ]]; then
  # Refuse rather than silently wiping: a previous restore may still be under
  # examination, and it may be the only copy of something.
  die "${RESTORE_DIR} exists and is not empty. Inspect it, then remove it by hand:
       sudo -u postgres ${PG_BIN}/pg_ctl -D '${RESTORE_DIR}' stop 2>/dev/null || true
       rm -rf '${RESTORE_DIR}'"
fi
mkdir -p "${RESTORE_DIR}"
chmod 0700 "${RESTORE_DIR}"

# --- Restore ---------------------------------------------------------------
stage "pgbackrest restore (target: ${TARGET})"

if [[ "${TARGET}" == "latest" ]]; then
  pgbackrest --stanza="${STANZA}" \
    --pg1-path="${RESTORE_DIR}" \
    --type=default \
    restore
else
  # --target-action=promote: finish recovery and open read/write, rather than
  # pausing. Without it the cluster sits in recovery and every write fails,
  # which reads like a broken restore.
  pgbackrest --stanza="${STANZA}" \
    --pg1-path="${RESTORE_DIR}" \
    --type=time \
    --target="${TARGET}" \
    --target-action=promote \
    restore
fi

# --- Start the scratch cluster ---------------------------------------------
stage "start scratch cluster on port ${RESTORE_PORT}"

# archive_mode=off is essential: a restored cluster left archiving would push
# WAL from a divergent timeline into the live repository and corrupt it.
"${PG_BIN}/pg_ctl" -D "${RESTORE_DIR}" \
  -o "-p ${RESTORE_PORT} -c archive_mode=off -c listen_addresses=localhost" \
  -l "${RESTORE_DIR}/restore.log" \
  -w -t 300 start \
  || die "cluster failed to start — see ${RESTORE_DIR}/restore.log"

# --- Wait for recovery to complete -----------------------------------------
stage "wait for recovery to finish"
for _ in $(seq 1 60); do
  if [[ "$(psql -p "${RESTORE_PORT}" -Atc 'SELECT pg_is_in_recovery()' postgres 2>/dev/null)" == "f" ]]; then
    break
  fi
  sleep 5
done

[[ "$(psql -p "${RESTORE_PORT}" -Atc 'SELECT pg_is_in_recovery()' postgres 2>/dev/null)" == "f" ]] \
  || die "still in recovery after 5 minutes — check ${RESTORE_DIR}/restore.log"

# --- Report ----------------------------------------------------------------
stage "verify"

readonly MIGRATION="$(psql -p "${RESTORE_PORT}" -Atc \
  'SELECT max(version::numeric) FROM flyway_schema_history' bunnycal 2>/dev/null || echo '?')"

cat <<EOF

  Restore complete.

    Data directory : ${RESTORE_DIR}
    Port           : ${RESTORE_PORT}
    Target         : ${TARGET}
    Flyway version : ${MIGRATION}

  Connect:
    psql -p ${RESTORE_PORT} bunnycal

  Verify before trusting this (docs/runbooks/disaster-recovery.md §4):
    - Row counts on bookings / users look plausible for the target time.
    - The token-decrypt canary passes — proves CALENDAR_TOKEN_ENCRYPTION_KEY_BASE64
      still matches this data. A mismatch is silent until the first calendar sync.
    - The Flyway version above is NOT newer than the image you intend to run.
      Newer database + older image = startup failure, no down-migrations exist.

  Tear down when finished:
    ${PG_BIN}/pg_ctl -D ${RESTORE_DIR} stop
    rm -rf ${RESTORE_DIR}

EOF

log "restore completed successfully"
