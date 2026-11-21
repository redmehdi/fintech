"""Offline CLI orchestration tests; fake agents never contact external services."""
from pathlib import Path
import json
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


def run(args, cwd, env=None):
    return subprocess.run(args, cwd=cwd, env=env, text=True, capture_output=True, timeout=30)


class PairCliTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = Path(self.tmp.name) / "repo"
        self.repo.mkdir()
        for sub in ["scripts", "docs", ".ai"]:
            shutil.copytree(ROOT / sub, self.repo / sub)
        shutil.copy2(ROOT / "AGENTS.md", self.repo / "AGENTS.md")
        shutil.copy2(ROOT / "CLAUDE.md", self.repo / "CLAUDE.md")
        shutil.copy2(ROOT / "README.md", self.repo / "README.md")
        shutil.copy2(ROOT / ".gitignore", self.repo / ".gitignore")
        shutil.copytree(ROOT / "contracts", self.repo / "contracts")
        (self.repo / "tests").mkdir()
        (self.repo / "tests" / "test_placeholder.py").write_text("import unittest\nclass S(unittest.TestCase):\n def test_ok(self): self.assertTrue(True)\n")
        run(["git", "init", "-b", "main"], self.repo)
        run(["git", "config", "user.name", "Test User"], self.repo)
        run(["git", "config", "user.email", "test@example.invalid"], self.repo)
        run(["git", "add", "."], self.repo)
        result = run(["git", "commit", "-m", "baseline"], self.repo)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.fakebin = Path(self.tmp.name) / "fakebin"
        self.fakebin.mkdir()
        self.env = dict(os.environ, PATH=str(self.fakebin) + os.pathsep + os.environ["PATH"], PAIR_VERIFY_CMD="true")
        self.agent("claude", """#!/usr/bin/env bash
set -euo pipefail
prompt=''
while (($#)); do
  if [[ "$1" == '-p' ]]; then shift; prompt="$1"; fi
  shift
done
if [[ "$prompt" == *'Codex reviewed'* ]]; then
  printf 'fixed\\n' > mock-fixed.txt
elif [[ "$prompt" == *'Independent review task'* ]]; then
  printf 'MOCK CLAUDE REVIEW: check contract compatibility\\n'
else
  printf 'implemented\\n' > mock-implemented.txt
fi
printf 'FAKE CLAUDE OK\\n' >&2
""")
        self.agent("codex", """#!/usr/bin/env python3
from pathlib import Path
import json
import os
import sys
import uuid

args = sys.argv[1:]
assert args[0] == 'exec'
sandbox = args[args.index('--sandbox') + 1]
resuming = 'resume' in args
if resuming:
    session_id = args[args.index('resume') + 1]
    uuid.UUID(session_id)
else:
    session_id = str(uuid.uuid4())
with Path('.ai-runs/codex-calls.jsonl').open('a') as calls:
    print(json.dumps([sandbox, resuming, session_id]), file=calls)

if sandbox == 'read-only':
    assert args[-1] == '-'
    prompt = sys.stdin.read()
    assert 'Independent review task' in prompt and '{{TICKET_ID}}' not in prompt
    if os.getenv('FAIL_REVIEW') == '1' or (resuming and os.getenv('FAIL_RESUME') == '1'):
        sys.exit(15)
    Path(args[args.index('-o') + 1]).write_text('FINDING: check tests\\n')
elif resuming:
    assert sandbox == 'workspace-write'
    Path('mock-codex-fixed.txt').write_text('fixed\\n')
else:
    assert sandbox == 'workspace-write'
    Path('mock-codex-implemented.txt').write_text('implemented\\n')

if '--json' in args:
    if resuming and os.getenv('WRONG_SESSION') == '1':
        session_id = str(uuid.uuid4())
    print(json.dumps({'type': 'thread.started', 'thread_id': session_id}))
""")

    def agent(self, name, body):
        filename = self.fakebin / name
        filename.write_text(body)
        filename.chmod(0o755)

    def task_branch(self, ticket="FX-001"):
        result = run(["bash", "scripts/new-task.sh", ticket], self.repo, self.env)
        self.assertEqual(result.returncode, 0, result.stderr)

    def codex_calls(self):
        trace = self.repo / ".ai-runs" / "codex-calls.jsonl"
        return [json.loads(line) for line in trace.read_text().splitlines()]

    def review(self, *options, ticket="FX-001", env=None):
        return run(
            ["bash", "scripts/pair.sh", ticket, "--skip-implement", "--fix-rounds", "0", *options],
            self.repo, env or self.env,
        )

    def test_dry_run_does_not_execute_agents(self):
        self.task_branch()
        result = run(["bash", "scripts/pair.sh", "FX-001", "--dry-run"], self.repo, self.env)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("codex exec", result.stdout)
        self.assertFalse((self.repo / "mock-implemented.txt").exists())

    def test_fx003_dry_run_uses_codex_then_claude(self):
        self.task_branch("FX-003")
        (self.repo / "existing-work.txt").write_text("dry runs are read-only\n")
        result = run(["bash", "scripts/pair.sh", "FX-003", "--dry-run"], self.repo, self.env)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertLess(result.stdout.index("Codex: codex exec --json"), result.stdout.index("Reviewer: Claude"))
        self.assertIn("codex exec --sandbox workspace-write resume <session-id>", result.stdout)
        self.assertFalse((self.repo / "mock-codex-implemented.txt").exists())

    def test_fx003_full_cycle_resumes_codex_after_claude_review(self):
        self.task_branch("FX-003")
        result = run(["bash", "scripts/pair.sh", "FX-003"], self.repo, self.env)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue((self.repo / "mock-codex-implemented.txt").exists())
        self.assertTrue((self.repo / "mock-codex-fixed.txt").exists())
        reports = list((self.repo / ".ai-runs").glob("*FX-003-*/review-1.md"))
        self.assertEqual(len(reports), 1)
        self.assertIn("MOCK CLAUDE REVIEW", reports[0].read_text())
        session_files = list((self.repo / ".ai-runs").glob("*FX-003-*/codex-session-id.txt"))
        self.assertEqual(len(session_files), 1)
        session_id = session_files[0].read_text().strip()
        self.assertEqual(self.codex_calls(), [
            ["workspace-write", False, session_id],
            ["workspace-write", True, session_id],
        ])

    def test_full_cycle_reuses_codex_session_and_does_not_commit(self):
        self.task_branch()
        result = run(["bash", "scripts/pair.sh", "FX-001", "--fix-rounds", "2"], self.repo, self.env)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue((self.repo / "mock-implemented.txt").exists())
        self.assertTrue((self.repo / "mock-fixed.txt").exists())
        calls = self.codex_calls()
        session_id = calls[0][2]
        self.assertEqual(calls, [
            ["read-only", False, session_id],
            ["read-only", True, session_id],
            ["read-only", True, session_id],
        ])
        self.assertIn(f"codex resume --sandbox workspace-write {session_id}", result.stderr)
        run_dir = next((self.repo / ".ai-runs").glob("*FX-001-*"))
        self.assertEqual((run_dir / "codex-session-id.txt").read_text().strip(), session_id)
        for round_number in range(3):
            self.assertIn("FINDING", (run_dir / f"review-{round_number}.md").read_text())
        self.assertFalse((self.repo / ".ai-runs" / ".lock").exists())
        self.assertNotEqual(run(["git", "status", "--porcelain"], self.repo).stdout.strip(), "")
        self.assertEqual(run(["git", "rev-list", "--count", "HEAD"], self.repo).stdout.strip(), "1")

    def test_refuses_dirty_initial_tree(self):
        self.task_branch()
        (self.repo / "another.txt").write_text("existing\n")
        result = run(["bash", "scripts/pair.sh", "FX-001"], self.repo, self.env)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("clean feature branch", result.stderr)

    def test_can_review_existing_changes_without_impl(self):
        self.task_branch()
        (self.repo / "manual.txt").write_text("manual\n")
        result = self.review()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse((self.repo / "mock-implemented.txt").exists())
        self.assertTrue(list((self.repo / ".ai-runs").glob("*FX-001-*/review-0.md")))

        (self.repo / "manual.txt").write_text("updated after review\n")
        result = self.review()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        first, follow_up = self.codex_calls()
        self.assertEqual(follow_up, ["read-only", True, first[2]])

    def test_review_sessions_are_scoped_to_ticket_and_branch(self):
        self.task_branch()
        (self.repo / "manual.txt").write_text("manual\n")
        self.assertEqual(self.review().returncode, 0)
        self.assertEqual(self.review(ticket="FX-004").returncode, 0)
        self.assertEqual(run(["git", "switch", "-c", "feature/another"], self.repo).returncode, 0)
        self.assertEqual(self.review().returncode, 0)
        self.assertEqual(run(["git", "switch", "feature/fx-001"], self.repo).returncode, 0)
        self.assertEqual(self.review().returncode, 0)
        first, other_ticket, other_branch, returning = self.codex_calls()
        self.assertEqual(len({first[2], other_ticket[2], other_branch[2]}), 3)
        self.assertEqual(returning, ["read-only", True, first[2]])

    def test_failed_resume_keeps_session_until_explicit_reset(self):
        self.task_branch()
        (self.repo / "manual.txt").write_text("manual\n")
        self.assertEqual(self.review().returncode, 0)
        result = self.review(env=dict(self.env, FAIL_RESUME="1"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("--new-session", result.stderr)
        self.assertFalse((self.repo / ".ai-runs" / ".lock").exists())
        result = self.review(env=dict(self.env, WRONG_SESSION="1"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("instead of", result.stderr)
        self.assertEqual(self.review("--new-session").returncode, 0)
        self.assertEqual(self.review().returncode, 0)
        first, failed, mismatch, reset, after_reset = self.codex_calls()
        self.assertEqual(failed, ["read-only", True, first[2]])
        self.assertEqual(mismatch, failed)
        self.assertFalse(reset[1])
        self.assertNotEqual(reset[2], first[2])
        self.assertEqual(after_reset, ["read-only", True, reset[2]])

    def test_review_failure_propagates_and_unlocks(self):
        self.task_branch()
        env = dict(self.env, FAIL_REVIEW="1")
        result = run(["bash", "scripts/pair.sh", "FX-001"], self.repo, env)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("review failed", result.stderr)
        self.assertFalse((self.repo / ".ai-runs" / ".lock").exists())

    def test_final_verification_failure_blocks_success(self):
        self.task_branch()
        env = dict(self.env, PAIR_VERIFY_CMD="false")
        result = run(["bash", "scripts/pair.sh", "FX-001", "--fix-rounds", "0"], self.repo, env)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Final local tests FAIL", result.stderr)
        self.assertFalse((self.repo / ".ai-runs" / ".lock").exists())

    def test_prevents_unexpected_agent_commit(self):
        self.task_branch()
        self.agent("claude", """#!/usr/bin/env bash
set -euo pipefail
printf 'bad\n' > generated.txt
git add generated.txt
git commit -m 'unexpected agent commit' >/dev/null
""")
        result = run(["bash", "scripts/pair.sh", "FX-001"], self.repo, self.env)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("changed HEAD", result.stderr)

    def test_rejects_invalid_ticket_and_main_branch(self):
        self.assertNotEqual(run(["bash", "scripts/new-task.sh", "FX-999"], self.repo, self.env).returncode, 0)
        result = run(["bash", "scripts/pair.sh", "FX-001", "--dry-run"], self.repo, self.env)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("feature/*", result.stderr)


if __name__ == '__main__':
    unittest.main()
