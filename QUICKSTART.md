# EUR↔MAD Fintech · Claude + Codex CLI Pair Programming

This package includes the original finance architecture and a scripted **Claude implement → Codex review → Claude repair → final Codex review** loop. **No real funds move**. Agents do not merge, push, commit or deploy.

## Requirements (Linux)

- bash 4+, Git, Python 3.9+, Node.js/npm, Java 21; Maven/Maven wrapper for the Java backend.
- Authenticated Claude Code and OpenAI Codex CLIs. Install using the **official** install guides:
  - Claude: https://code.claude.com/docs/en/setup
  - Codex: https://learn.chatgpt.com/docs/cli/
- You need access/billing for *each* AI service as applicable. Use `claude auth login` and `codex login` if not authenticated.
- First time: run only in a fresh, trusted Git checkout without confidential files. Check executable prompts and review CLI permissions.

## Step 1 · Clone/unzip and baseline Git commit

```bash
unzip eur-mad-fintech-pair-cli.zip
cd eur-mad-fintech-pair-cli
git init -b main
git add .
git commit -m 'chore: import simulation-only architecture and pair CLI'
```

If Git asks for identity, configure `git config user.name ...` and `git config user.email ...` for this repository.

## Step 2 · Doctor and offline smoke tests

```bash
bash scripts/doctor.sh
bash scripts/verify.sh --tooling-only
bash scripts/new-task.sh FX-001
bash scripts/pair.sh FX-001 --dry-run
# Same commands using Make: make doctor, make check, make dry-run ID=FX-001
```

## Step 3 · Actual pair-programming cycle

```bash
bash scripts/pair.sh FX-001
# Or: make pair ID=FX-001
```

This follows the implementer/reviewer assignment in `docs/BACKLOG.md`. For most tickets it runs Claude implementation → local checks → read-only Codex review → Claude repair → checks and final Codex review. FX-003 uses Codex implementation → local checks → read-only Claude review → Codex resume/repair → checks and final Claude review. Codex resumes the implementation session id captured from its JSONL event stream. Reports, prompts and the Codex session id are in `.ai-runs/` and ignored by Git. Read the final review before deciding to commit.

Preview role selection without launching either agent:

```bash
bash scripts/pair.sh FX-003 --dry-run
```

## Step 4 · After your review

```bash
git status --short
git diff --check
git diff
git add -A
git diff --cached
git commit -m 'feat(FX-001): scaffold frontend and backend'
```

Do NOT commit `node_modules`, credentials or user identity/financial data. `git add -A` should happen only after checking `git status` and ignored patterns.

To start the next ticket, finish prior changes, switch to main, merge the approved feature branch manually, then run `scripts/new-task.sh FX-002`.

## Alternative: manual implementation, automated review

```bash
claude
# Implement assigned ticket interactively; exit Claude without committing.
bash scripts/pair.sh FX-001 --skip-implement --fix-rounds 0
```

## Customize and debug

- `--fix-rounds 0`: only the first assigned review; `--fix-rounds 2`: two bounded fixes and three reviews.
- `PAIR_CLAUDE_PERMISSION_MODE=auto`: asks Claude's classifier to decide individual operations. Never use `bypassPermissions` for money-related work.
- `PAIR_CLAUDE_ALLOWED_TOOLS`: override scoped tool allowances carefully. The defaults permit npm and Maven commands, **which can execute third-party scripts**, so use a trusted local workspace.
- `PAIR_VERIFY_CMD='bash scripts/verify.sh --tooling-only'`: testing override for maintaining the CLI itself (NOT for production acceptance).
- Logs are local only: `.ai-runs/...`; they may still contain code, prompts and diagnostic data. Do not publish them.
- `scripts/verify.sh --require-scaffold` demands `apps/web/package.json` and Spring Boot Maven/Gradle build, tests, frontend lint/typecheck when present, and frontend production build. It does NOT install dependencies; initialize/install them in the repo first. If dependency installation fails, fix it and re-run review using `--skip-implement`.
- `scripts/doctor.sh` checks requirements but does not verify model-account subscription/billing or regulatory readiness.

## Safety / architecture

- Simulation-only: no wallets, custody, real payment transfer, settlement, P2P off-platform netting, or real MAD→EUR cross-border movement.
- Only enable real payments after a separately approved licensed-partner/legal workstream.
- The native `codex exec review --uncommitted` generates advice, **not a pass/fail compliance gate**. A zero exit code means the reviewer ran, not that the change was approved.
- The workflow has no autonomous merge; human must assess findings and CI/security output before any commit or release.
- Scripted shell permission settings are useful guardrails but not a secure sandbox against untrusted repository code. Use a container/VM and minimal credentials for sensitive production work.

## Documentation

- `.ai/prompts/implement.md`: implementation instruction for the assigned agent.
- `.ai/prompts/fix.md`: how the assigned implementer consumes review findings.
- `.ai/prompts/review.md`: read-only Claude review instruction for FX-003.
- `.ai/prompts/review_policy.md`: review focus (also described in AGENTS.md; Codex's built-in review target cannot simultaneously accept an additional freeform prompt).
- `docs/BACKLOG.md`: tickets FX-001 through FX-020.
- `tests/test_pair_cli.py`: isolated offline regression tests with stub CLIs.
