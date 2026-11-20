#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
fail=0
check(){ if command -v "$1" >/dev/null 2>&1; then printf 'OK      %-10s %s\n' "$1" "$(command -v "$1")"; else printf 'MISSING %-10s\n' "$1"; fail=1; fi; }
for prog in bash git python3 node npm java claude codex; do check "$prog"; done
if command -v java >/dev/null 2>&1; then java -version 2>&1 | head -n 1 || true; fi
for file in AGENTS.md CLAUDE.md docs/PRD.md docs/BACKLOG.md contracts/openapi.yaml; do
 [[ -f "$file" ]] && printf 'OK      %s\n' "$file" || { printf 'MISSING %s\n' "$file"; fail=1; }
done
if command -v claude >/dev/null 2>&1; then
 if claude auth status >/dev/null 2>&1; then echo 'OK      Claude auth'; else echo 'CHECK   Claude auth: run claude auth login'; fi
fi
if command -v codex >/dev/null 2>&1; then
 if codex login status >/dev/null 2>&1; then echo 'OK      Codex auth'; else echo 'CHECK   Codex auth: run codex login'; fi
fi
if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
 echo "GIT     branch: $(git branch --show-current)"
else
 echo 'GIT     not initialized: follow QUICKSTART.md'
fi
exit "$fail"
