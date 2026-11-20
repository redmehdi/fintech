#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

die(){ printf 'ERROR: %s\n' "$*" >&2; exit 1; }
note(){ printf '[pair] %s\n' "$*" >&2; }
check_ticket(){ [[ "${1:-}" =~ ^FX-[0-9][0-9][0-9]$ ]] || die 'Ticket must match FX-001'; grep -Fq "${1} ·" docs/BACKLOG.md || die "Ticket $1 not in docs/BACKLOG.md"; }
assert_git(){ git rev-parse --is-inside-work-tree >/dev/null 2>&1 || die 'Initialize git and make a baseline commit first; see QUICKSTART.md'; git rev-parse --verify HEAD >/dev/null 2>&1 || die 'Make the baseline commit first'; }
assert_feature_branch(){
 local branch; branch="$(git branch --show-current)"
 [[ "$branch" == feature/* ]] || die "Expected feature/* branch, found: ${branch:-detached HEAD}. Use scripts/new-task.sh FX-001"
}
render_prompt(){
 local template="$1" ticket="$2" review_path="${3:-}" body
 body="$(cat "$template")"
 body="${body//\{\{TICKET_ID\}\}/$ticket}"
 body="${body//\{\{REVIEW_PATH\}\}/$review_path}"
 printf '%s\n' "$body"
}
