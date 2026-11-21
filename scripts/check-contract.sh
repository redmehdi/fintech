#!/usr/bin/env bash
set -euo pipefail

base_sha="${1:?Usage: bash scripts/check-contract.sh BASE_REVISION [OPENAPI_FILE]}"
revision="${2:-contracts/openapi.yaml}"
if [[ "${GITHUB_EVENT_NAME:-}" == push && "$base_sha" =~ ^0+$ ]]; then
  if ! git cat-file -e HEAD^:contracts/openapi.yaml 2>/dev/null; then
    printf '%s\n' '::notice::No preceding OpenAPI contract on this initial branch push.'
    exit 0
  fi
  base_sha=HEAD^
fi

baseline="$(mktemp "${TMPDIR:-/tmp}/openapi-base.XXXXXXXX.yaml")"
trap 'rm -f "$baseline"' EXIT
if [[ "${GITHUB_EVENT_NAME:-}" == push ]] && ! git cat-file -e "$base_sha^{commit}" 2>/dev/null; then
  # A force-push can remove the previous tip from the checked-out history.
  gh api -H 'Accept: application/vnd.github.raw+json' \
    "repos/${GITHUB_REPOSITORY}/contents/contracts/openapi.yaml?ref=$base_sha" > "$baseline"
else
  git show "$base_sha:contracts/openapi.yaml" > "$baseline"
fi

oasdiff breaking --format json --fail-on WARN -- "$baseline" "$revision"
