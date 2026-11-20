"""Offline CLI orchestration tests; fake agents never contact external services."""
from pathlib import Path
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
else
  printf 'implemented\\n' > mock-implemented.txt
fi
printf 'FAKE CLAUDE OK\\n'
""")
        self.agent("codex", """#!/usr/bin/env bash
set -euo pipefail
[[ "${FAIL_REVIEW:-0}" != '1' ]] || exit 15
file=''
while (($#)); do
  if [[ "$1" == '-o' ]]; then shift; file="$1"; fi
  shift
done
[[ -n "$file" ]] || exit 2
printf 'FINDING: check tests; mock review\\n' > "$file"
printf 'FAKE CODEX OK\\n'
""")

    def agent(self, name, body):
        filename = self.fakebin / name
        filename.write_text(body)
        filename.chmod(0o755)

    def task_branch(self):
        result = run(["bash", "scripts/new-task.sh", "FX-001"], self.repo, self.env)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_dry_run_does_not_execute_agents(self):
        self.task_branch()
        result = run(["bash", "scripts/pair.sh", "FX-001", "--dry-run"], self.repo, self.env)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("codex exec", result.stdout)
        self.assertFalse((self.repo / "mock-implemented.txt").exists())

    def test_full_cycle_two_reviews_and_no_commit(self):
        self.task_branch()
        result = run(["bash", "scripts/pair.sh", "FX-001"], self.repo, self.env)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue((self.repo / "mock-implemented.txt").exists())
        self.assertTrue((self.repo / "mock-fixed.txt").exists())
        logs = list((self.repo / ".ai-runs").glob("*FX-001-*/review-1.md"))
        self.assertEqual(len(logs), 1)
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
        result = run(["bash", "scripts/pair.sh", "FX-001", "--skip-implement", "--fix-rounds", "0"], self.repo, self.env)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse((self.repo / "mock-implemented.txt").exists())
        self.assertTrue(list((self.repo / ".ai-runs").glob("*FX-001-*/review-0.md")))

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
