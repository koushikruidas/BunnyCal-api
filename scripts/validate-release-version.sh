#!/usr/bin/env bash
set -euo pipefail

# ---------------------------------------------------------------------------
# Validate a release version string for the production CI image pipeline.
#
# Source of truth: the git tag that triggered the release (e.g. `v1.0.0`).
# This script is given the version WITHOUT the leading `v` (the workflow strips
# it). It accepts clean semantic versions and rejects snapshot/dev/placeholder
# versions so that SNAPSHOT or "dev" images can never be published as a release.
#
# Usage:   scripts/validate-release-version.sh <version>
# Example: scripts/validate-release-version.sh 1.0.0
#
# Valid:    1.0.0   1.2.4   2.0.0-beta1   2.1.0-rc.1
# Invalid:  1.0.0-SNAPSHOT   0.0.1-SNAPSHOT   dev   latest-only   v1.0.0
# ---------------------------------------------------------------------------

if [[ $# -ne 1 || -z "${1:-}" ]]; then
  echo "::error::usage: $0 <version>  (e.g. 1.0.0)" >&2
  exit 2
fi

version="$1"

# Reject anything that looks like a snapshot or non-release placeholder early,
# with a clear message (these are the cases the spec calls out explicitly).
case "$version" in
  *-SNAPSHOT|*-snapshot)
    echo "::error::Refusing to publish a SNAPSHOT version: '$version'. Tag a clean release like v1.0.0." >&2
    exit 1
    ;;
  dev|DEV|latest|latest-only)
    echo "::error::Refusing to publish placeholder version: '$version'. Tag a clean release like v1.0.0." >&2
    exit 1
    ;;
esac

# Strict semantic version: MAJOR.MINOR.PATCH with an optional pre-release suffix
# of dot-separated alphanumeric identifiers (e.g. -beta1, -rc.1, -alpha.2).
# Build metadata (+...) and a leading 'v' are intentionally NOT accepted here.
semver_re='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z]+(\.[0-9A-Za-z]+)*)?$'

if [[ ! "$version" =~ $semver_re ]]; then
  echo "::error::Invalid release version: '$version'. Expected MAJOR.MINOR.PATCH[-prerelease], e.g. 1.0.0 or 2.0.0-beta1." >&2
  exit 1
fi

echo "Release version OK: $version"
