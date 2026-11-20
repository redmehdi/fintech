#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
usage(){ cat <<'EOF'
Usage: scripts/pair.sh FX-001 [--dry-run] [--skip-implement] [--fix-rounds 0|1|2]
  Default: Claude implement -> verify -> Codex review -> Claude fix -> verify -> Codex final review.
  --skip-implement: review existing uncommitted changes (does not require clean tree)
  --fix-rounds 0: review-only after implementation, no repair or final review
  No commit, push, merge, or deploy is performed. Any review findings need human signoff.
EOF
}
[[ $# -ge 1 ]] || { usage; exit 2; }
ticket="$1"; shift
check_ticket "$ticket"
dry_run=0; skip_implement=0; fix_rounds=1
while (($#)); do
 case "$1" in
 --dry-run) dry_run=1 ;;
 --skip-implement) skip_implement=1 ;;
 --fix-rounds) shift; [[ $# -gt 0 ]] || die 'Missing value for --fix-rounds'; fix_rounds="$1" ;;
 -h|--help) usage; exit 0 ;;
 *) die "Unknown option: $1" ;;
 esac; shift
done
[[ "$fix_rounds" =~ ^[012]$ ]] || die '--fix-rounds must be 0, 1 or 2'
assert_git
assert_feature_branch
original_head="$(git rev-parse HEAD)"
original_branch="$(git branch --show-current)"
assert_revision_unchanged(){
  [[ "$(git rev-parse HEAD)" == "$original_head" ]] || die "Agent or test command changed HEAD. Stop and inspect git log; no automatic rollback was performed."
  [[ "$(git branch --show-current)" == "$original_branch" ]] || die "Git branch changed during run. Stop and inspect."
}
if [[ "$skip_implement" == 0 && -n "$(git status --porcelain)" ]]; then
 die 'Implementation starts only on a clean feature branch. Use --skip-implement to review current changes.'
fi
if [[ "$dry_run" == 1 ]]; then
 cat <<EOF
DRY RUN for $ticket
  Claude: claude -p <implement prompt> --permission-mode acceptEdits --allowedTools <scoped tools>
  Verify: scripts/verify.sh --require-scaffold
  Codex: codex exec --sandbox read-only review --uncommitted -o <review.md>
  Fix cycles: $fix_rounds (Claude reads review and fixes; verify and Codex review repeat)
  No Git commit/push/merge/deploy. Output in .ai-runs/<timestamp>-<ticket>/.
EOF
 exit 0
fi
command -v claude >/dev/null 2>&1 || die 'Claude CLI missing'
command -v codex >/dev/null 2>&1 || die 'Codex CLI missing'
# Prevent two orchestrators from operating in the same checkout.
mkdir -p .ai-runs
if ! mkdir .ai-runs/.lock 2>/dev/null; then die 'Another pair process may be running (check .ai-runs/.lock)'; fi
trap 'rmdir .ai-runs/.lock 2>/dev/null || true' EXIT
run_id="$(date -u +%Y%m%dT%H%M%SZ)-${ticket}-$BASHPID"
run_dir="$(pwd)/.ai-runs/$run_id"
mkdir -p "$run_dir"
printf 'ticket=%s\nbranch=%s\nrun_id=%s\n' "$ticket" "$(git branch --show-current)" "$run_id" > "$run_dir/metadata.txt"
note "Logs: $run_dir"
# Claude can read/write project code, and run selected developer/test commands; it cannot be asked to skip permissions.
# Override via PAIR_CLAUDE_ALLOWED_TOOLS if your workflow needs more commands, after reviewing them.
CLAUDE_ALLOWED_TOOLS="${PAIR_CLAUDE_ALLOWED_TOOLS:-Read,Edit,Write,Glob,Grep,Bash(git status *),Bash(git diff *),Bash(git ls-files *),Bash(npm *),Bash(npx *),Bash(node *),Bash(mvn *),Bash(./mvnw *),Bash(./gradlew *),Bash(gradle *),Bash(java *),Bash(python3 *),Bash(mkdir *),Bash(ls *),Bash(find *),Bash(cat *),Bash(chmod *)}"
# These denials reduce accidental Git operations; they do not replace process isolation.
CLAUDE_DENIED_TOOLS="Bash(git commit *),Bash(git push *),Bash(git merge *),Bash(git rebase *),Bash(git checkout *),Bash(git switch *),Bash(git reset *),Bash(git clean *)"
CLAUDE_PERMISSION_MODE="${PAIR_CLAUDE_PERMISSION_MODE:-acceptEdits}"
case "$CLAUDE_PERMISSION_MODE" in acceptEdits|auto|dontAsk|default) ;; *) die 'Unsafe or unsupported permission mode override';; esac
run_claude(){
 local prompt_file="$1" log_file="$2"
 note "Claude: $(basename "$prompt_file")"
 claude -p "$(cat "$prompt_file")" --permission-mode "$CLAUDE_PERMISSION_MODE" \
  --allowedTools "$CLAUDE_ALLOWED_TOOLS" --disallowedTools "$CLAUDE_DENIED_TOOLS" > "$log_file" 2>&1 || { note "Claude failed; inspect $log_file"; return 1; }
}
run_tests(){
 local log="$1"; note 'Running local checks'
 if [[ -n "${PAIR_VERIFY_CMD:-}" ]]; then
   # For trusted local test harnesses. Not interpolated from ticket text.
   bash -c "$PAIR_VERIFY_CMD" > "$log" 2>&1
 else
   bash scripts/verify.sh --require-scaffold > "$log" 2>&1
 fi
}
review(){
 local output="$1" log="$2"
 note 'Codex: independent read-only review'
 codex exec --sandbox read-only review --uncommitted -o "$output" > "$log" 2>&1 || { note "Codex review failed; inspect $log"; return 1; }
 [[ -s "$output" ]] || { note "Codex returned an empty review: $log"; return 1; }
}
if [[ "$skip_implement" == 0 ]]; then
 render_prompt .ai/prompts/implement.md "$ticket" > "$run_dir/implement-prompt.md"
 run_claude "$run_dir/implement-prompt.md" "$run_dir/claude-implement.log"
fi
assert_revision_unchanged
[[ -n "$(git status --porcelain)" ]] || die 'No changes to review; Claude may not have implemented the ticket.'
# Preserve test status without stopping code review/repair on failed checks.
if run_tests "$run_dir/tests-initial.log"; then note 'Initial tests passed'; else note 'Initial tests failed; review and repair required'; fi
review "$run_dir/review-0.md" "$run_dir/codex-0.log"
for ((i=1;i<=fix_rounds;i++)); do
 render_prompt .ai/prompts/fix.md "$ticket" "$run_dir/review-$((i-1)).md" > "$run_dir/fix-prompt-$i.md"
 run_claude "$run_dir/fix-prompt-$i.md" "$run_dir/claude-fix-$i.log"
 assert_revision_unchanged
 if run_tests "$run_dir/tests-$i.log"; then note "Tests after repair $i passed"; else note "Tests after repair $i FAILED"; fi
 review "$run_dir/review-$i.md" "$run_dir/codex-$i.log"
done
assert_revision_unchanged
note 'Final verification'
if run_tests "$run_dir/tests-final.log"; then
 note 'Final local tests PASS'
else
 note "Final local tests FAIL. See $run_dir/tests-final.log"
 exit 1
fi
assert_revision_unchanged
note "Last independent review: $run_dir/review-$fix_rounds.md"
note 'Review findings manually. Nothing has been committed, pushed or merged.'
