#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
usage(){ cat <<'EOF'
Usage: scripts/pair.sh FX-### [--dry-run] [--skip-implement] [--fix-rounds 0|1|2] [--new-session]
  Default: Claude implements and repairs, Codex reviews in one session.
  FX-003: Codex implement -> verify -> Claude review -> Codex resume/fix -> verify -> Claude final review.
  --skip-implement: review existing changes and resume the saved Codex review session for this ticket and branch
  --new-session: start a fresh Codex review session with --skip-implement
  --fix-rounds 0: review-only after implementation, no repair or final review
  No commit, push, merge, or deploy is performed. Any review findings need human signoff.
EOF
}
[[ $# -ge 1 ]] || { usage; exit 2; }
ticket="$1"; shift
check_ticket "$ticket"
case "$ticket" in
 FX-003) implementer=codex; reviewer=claude; implementer_label=Codex; reviewer_label=Claude ;;
 *) implementer=claude; reviewer=codex; implementer_label=Claude; reviewer_label=Codex ;;
esac
dry_run=0; skip_implement=0; fix_rounds=1; new_session=0
while (($#)); do
 case "$1" in
 --dry-run) dry_run=1 ;;
 --skip-implement) skip_implement=1 ;;
 --new-session) new_session=1 ;;
 --fix-rounds) shift; [[ $# -gt 0 ]] || die 'Missing value for --fix-rounds'; fix_rounds="$1" ;;
 -h|--help) usage; exit 0 ;;
 *) die "Unknown option: $1" ;;
 esac; shift
done
[[ "$fix_rounds" =~ ^[012]$ ]] || die '--fix-rounds must be 0, 1 or 2'
if [[ "$new_session" == 1 && ( "$skip_implement" != 1 || "$reviewer" != codex ) ]]; then
 die '--new-session requires --skip-implement and a Codex review'
fi
assert_git
assert_feature_branch
original_head="$(git rev-parse HEAD)"
original_branch="$(git branch --show-current)"
assert_revision_unchanged(){
  [[ "$(git rev-parse HEAD)" == "$original_head" ]] || die "Agent or test command changed HEAD. Stop and inspect git log; no automatic rollback was performed."
  [[ "$(git branch --show-current)" == "$original_branch" ]] || die "Git branch changed during run. Stop and inspect."
}
if [[ "$dry_run" == 1 ]]; then
 cat <<EOF
DRY RUN for $ticket
EOF
 if [[ "$skip_implement" == 1 ]]; then
  printf '  Review existing uncommitted changes\n'
 elif [[ "$implementer" == codex ]]; then
  cat <<'EOF'
  Codex: codex exec --json --sandbox workspace-write <implement prompt>
EOF
 else
  cat <<'EOF'
  Claude: claude -p <implement prompt> --permission-mode acceptEdits --allowedTools <scoped tools>
EOF
 fi
 cat <<EOF
  Verify: scripts/verify.sh --require-scaffold
  Reviewer: $reviewer_label (read-only)
EOF
 if [[ "$reviewer" == claude ]]; then
  cat <<'EOF'
  Claude: claude -p <review prompt> --permission-mode plan --permission-prompts none
  Repair: codex exec --sandbox workspace-write resume <session-id> <fix prompt>
EOF
 else
  cat <<'EOF'
  First review: codex exec --sandbox read-only --json -o <review.md> <review prompt>
  Follow-up: codex exec --sandbox read-only resume <session-id> --json -o <review.md> <review prompt>
  Repair: Claude reads review and fixes with scoped edit tools
EOF
  if [[ "$skip_implement" == 1 && "$new_session" == 0 ]]; then
   printf '  Reuse the saved Codex review session for this ticket and branch when available.\n'
  fi
 fi
 cat <<EOF
  Fix cycles: $fix_rounds ($implementer_label repairs after $reviewer_label review; verification and review repeat)
  No Git commit/push/merge/deploy. Output in .ai-runs/<timestamp>-<ticket>/.
EOF
 exit 0
fi
if [[ "$skip_implement" == 0 && -n "$(git status --porcelain)" ]]; then
 die 'Implementation starts only on a clean feature branch. Use --skip-implement to review current changes.'
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
CODEX_SESSION_ID=""
valid_codex_session_id(){
 [[ "$1" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]]
}
if [[ "$reviewer" == codex ]]; then
 mkdir -p .ai-runs/sessions
 branch_key="$(printf '%s' "$original_branch" | git hash-object --stdin)"
 codex_review_session_file=".ai-runs/sessions/$ticket-$branch_key.txt"
 if [[ "$skip_implement" == 0 || "$new_session" == 1 ]]; then
  # A new implementation or explicit reset supersedes any previous review.
  : > "$codex_review_session_file"
 elif [[ -s "$codex_review_session_file" ]]; then
  CODEX_SESSION_ID="$(cat "$codex_review_session_file")"
  valid_codex_session_id "$CODEX_SESSION_ID" || die 'Invalid saved Codex session ID. Use --new-session to start a fresh review.'
 fi
fi
record_codex_session(){
 local trace_file="$1" reported_id
 reported_id="$(python3 - "$trace_file" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as trace:
    for line in trace:
        if line.strip():
            event = json.loads(line)
            if event.get("type") == "thread.started":
                print(event.get("thread_id", ""))
                break
PY
 )" || die "Could not read Codex session ID from $trace_file"
 valid_codex_session_id "$reported_id" || die "Codex did not report a resumable session ID. See $trace_file"
 if [[ -n "$CODEX_SESSION_ID" && "$reported_id" != "$CODEX_SESSION_ID" ]]; then
  die "Codex returned session $reported_id instead of $CODEX_SESSION_ID. See $trace_file"
 fi
 CODEX_SESSION_ID="$reported_id"
 printf '%s\n' "$CODEX_SESSION_ID" > "$run_dir/codex-session-id.txt"
}
run_codex_implementation(){
 local prompt_file="$1" trace_file="$2" log_file="$3"
 note 'Codex: implementation'
 codex exec --json --sandbox workspace-write "$(cat "$prompt_file")" > "$trace_file" 2> "$log_file" || { note "Codex failed; inspect $log_file and $trace_file"; return 1; }
 record_codex_session "$trace_file"
}
run_codex_fix(){
 local prompt_file="$1" log_file="$2"
 if [[ -z "$CODEX_SESSION_ID" ]]; then
  note 'No Codex implementation session to resume; starting a new repair session'
  run_codex_implementation "$prompt_file" "$run_dir/codex-fix.jsonl" "$log_file"
  return
 fi
 note "Codex: resuming implementation session $CODEX_SESSION_ID"
 codex exec --sandbox workspace-write resume "$CODEX_SESSION_ID" "$(cat "$prompt_file")" > "$log_file" 2>&1 || { note "Codex resume failed; inspect $log_file"; return 1; }
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
 local output="$1" log="$2" prompt_file="$3" trace_file
 assert_revision_unchanged
 render_prompt .ai/prompts/review.md "$ticket" > "$prompt_file"
 if [[ "$reviewer" == codex ]]; then
  trace_file="${log%.log}.jsonl"
  local -a codex_review=(codex exec --sandbox read-only)
  if [[ -n "$CODEX_SESSION_ID" ]]; then
   note "Codex: resuming read-only review session $CODEX_SESSION_ID"
   codex_review+=(resume "$CODEX_SESSION_ID")
  else
   note 'Codex: starting read-only review session'
  fi
  "${codex_review[@]}" --json -o "$output" - < "$prompt_file" > "$trace_file" 2> "$log" || {
   note "Codex review failed. See $log and $trace_file"
   if [[ -n "$CODEX_SESSION_ID" ]]; then note 'If the saved session is unavailable, use --skip-implement --new-session.'; fi
   return 1
  }
  assert_revision_unchanged
  record_codex_session "$trace_file"
  if [[ ! -s "$codex_review_session_file" ]]; then
   cp "$run_dir/codex-session-id.txt" "$codex_review_session_file.tmp"
   mv "$codex_review_session_file.tmp" "$codex_review_session_file"
  fi
 else
  note 'Claude: independent read-only review'
  claude -p "$(cat "$prompt_file")" --permission-mode plan --permission-prompts none \
   --allowedTools "Read,Glob,Grep,Bash(git status *),Bash(git diff *),Bash(git ls-files *)" \
   > "$output" 2> "$log" || { note "Claude review failed; inspect $log"; return 1; }
 fi
 [[ -s "$output" ]] || { note "$reviewer_label returned an empty review: $log"; return 1; }
}
if [[ "$skip_implement" == 0 ]]; then
 render_prompt .ai/prompts/implement.md "$ticket" > "$run_dir/implement-prompt.md"
 if [[ "$implementer" == codex ]]; then
  run_codex_implementation "$run_dir/implement-prompt.md" "$run_dir/codex-implement.jsonl" "$run_dir/codex-implement.log"
 else
  run_claude "$run_dir/implement-prompt.md" "$run_dir/claude-implement.log"
 fi
fi
assert_revision_unchanged
[[ -n "$(git status --porcelain)" ]] || die 'No changes to review; Claude may not have implemented the ticket.'
# Preserve test status without stopping code review/repair on failed checks.
if run_tests "$run_dir/tests-initial.log"; then note 'Initial tests passed'; else note 'Initial tests failed; review and repair required'; fi
review "$run_dir/review-0.md" "$run_dir/reviewer-0.log" "$run_dir/review-prompt-0.md"
if [[ "$reviewer" == codex ]]; then
 note "To continue with Codex interactively after the run, use: codex resume --sandbox workspace-write $CODEX_SESSION_ID"
fi
for ((i=1;i<=fix_rounds;i++)); do
 render_prompt .ai/prompts/fix.md "$ticket" "$run_dir/review-$((i-1)).md" "$reviewer_label" > "$run_dir/fix-prompt-$i.md"
 if [[ "$implementer" == codex ]]; then
  run_codex_fix "$run_dir/fix-prompt-$i.md" "$run_dir/codex-fix-$i.log"
 else
  run_claude "$run_dir/fix-prompt-$i.md" "$run_dir/claude-fix-$i.log"
 fi
 assert_revision_unchanged
 if run_tests "$run_dir/tests-$i.log"; then note "Tests after repair $i passed"; else note "Tests after repair $i FAILED"; fi
 review "$run_dir/review-$i.md" "$run_dir/reviewer-$i.log" "$run_dir/review-prompt-$i.md"
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
note "Last review: $run_dir/review-$fix_rounds.md"
note 'Review findings manually. Nothing has been committed, pushed or merged.'
