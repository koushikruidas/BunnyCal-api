#!/usr/bin/env bash
set -euo pipefail

# Guard shared-path regressions: provider literals must not creep back into
# repository/service paths that should be provider-parameterized.
TARGETS=(
  "src/main/java/com/daedalussystems/BunnyCal/booking/repository/BookingRepository.java"
  "src/main/java/com/daedalussystems/BunnyCal/booking/service/PublicBookingService.java"
)

violations=0
for file in "${TARGETS[@]}"; do
  if rg -n "'google'|\"google\"" "$file" >/dev/null; then
    echo "Hardcoded google literal found in $file"
    rg -n "'google'|\"google\"" "$file"
    violations=1
  fi
done

if [[ $violations -ne 0 ]]; then
  echo "\nUse provider resolution/parameters instead of hardcoded literals in shared paths."
  exit 1
fi

echo "OK: no hardcoded google literals in shared paths"
