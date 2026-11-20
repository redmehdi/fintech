#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
ticket="${1:-}"
check_ticket "$ticket"
assert_git
[[ -z "$(git status --porcelain)" ]] || die 'Dirty working tree. Commit or stash existing changes first.'
branch="feature/${ticket,,}"
if git show-ref --verify --quiet "refs/heads/$branch"; then die "Branch already exists: $branch"; fi
git switch -c "$branch"
note "Created $branch. Run: scripts/pair.sh $ticket"
