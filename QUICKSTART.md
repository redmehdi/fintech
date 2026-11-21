# EUR↔MAD Fintech · Claude + Codex CLI Pair Programming

This package includes the original finance architecture and a scripted **Claude implement → Codex review → Claude repair → resume Codex review** loop. **No real funds move**. Agents do not merge, push, commit or deploy.

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

The default loop runs Claude implementation and repair with read-only Codex reviews. Every review after the first resumes the same Codex session and checks the current changes. FX-003 uses Codex implementation and repair with read-only Claude reviews. Codex resumes its implementation session for repairs on that ticket.

The run directory under `.ai-runs/` contains the reports, prompts, and `codex-session-id.txt`. Read the final review before deciding to commit.

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
# Or: make review ID=FX-001
```

Repeat `make review ID=FX-001` after further edits to reuse the saved Codex review session for the same ticket and branch in this checkout. The first review creates a session when none is saved. To start fresh, add `--new-session` to the script command above.

To ask Codex for a scoped improvement or fix, run the `codex resume --sandbox workspace-write <session-id>` command printed by the script from the same checkout. That session has the review findings and can continue interactively. If Codex makes edits, have an independent reviewer check them before sign-off.

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
- Codex reviews use a read-only session and follow the repository review policy. A human still assesses findings and signs off.
- The workflow has no autonomous merge; human must assess findings and CI/security output before any commit or release.
- Scripted shell permission settings are useful guardrails but not a secure sandbox against untrusted repository code. Use a container/VM and minimal credentials for sensitive production work.

## Documentation

- `.ai/prompts/implement.md`: implementation instruction for the assigned agent.
- `.ai/prompts/fix.md`: how the assigned implementer consumes review findings.
- `.ai/prompts/review.md`: read-only review instructions for both agents.
- `.ai/prompts/review_policy.md`: review focus, also described in AGENTS.md.
- `docs/BACKLOG.md`: tickets FX-001 through FX-020.
- `tests/test_pair_cli.py`: isolated offline regression tests with stub CLIs.
