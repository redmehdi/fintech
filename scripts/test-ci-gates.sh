#!/usr/bin/env bash
set -euo pipefail

tmp="$(mktemp -d)"
type_error=''
trap 'rm -f "$type_error" "$tmp/breaking.yaml" "$tmp/contract.log" "$tmp/typecheck.log"; rmdir "$tmp"' EXIT

# Drop an operation while keeping the revised spec valid.
awk '/^  \/v1\/me:$/ { drop = 1; next } /^  \/|^components:/ { drop = 0 } !drop' \
  contracts/openapi.yaml > "$tmp/breaking.yaml"
if bash scripts/check-contract.sh HEAD "$tmp/breaking.yaml" > "$tmp/contract.log" 2>&1 ||
    ! grep -Fq 'api-removed-without-deprecation' "$tmp/contract.log"; then
  cat "$tmp/contract.log"
  printf '%s\n' 'Expected the compatibility gate to reject the removed operation.' >&2
  exit 1
fi
printf '%s\n' 'Compatibility gate rejected the removed operation.'

type_error="$(mktemp apps/web/fx003-ci-typecheck-XXXXXXXX.ts)"
cat > "$type_error" <<'TS'
import type { QuoteRequest } from "./src/lib/api/generated";

export const invalidMinorUnits: QuoteRequest["sourceAmountMinor"] = 42;
TS
if make verify > "$tmp/typecheck.log" 2>&1 ||
    ! grep -Eq "$(basename "$type_error" .ts)\.ts\([0-9]+,[0-9]+\): error TS2322:" "$tmp/typecheck.log"; then
  cat "$tmp/typecheck.log"
  printf '%s\n' 'Expected make verify to reject numeric minor units during type-check.' >&2
  exit 1
fi
printf '%s\n' 'Type-check gate rejected numeric minor units.'
