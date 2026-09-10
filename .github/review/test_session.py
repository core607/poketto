import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import review
import review_session
from review_scope import core_diff, is_core
from test_review import FakeGitHub, FakeProvider


class SessionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # A real Git graph is needed to prove that test-only pushes cannot hide earlier code.
        import subprocess
        cls.temp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temp.name)
        cls.git = staticmethod(lambda *args: subprocess.check_output(["git", *args], cwd=cls.root,
                            stderr=subprocess.DEVNULL).decode().strip())
        cls.git("init", "-b", "main")
        cls.git("config", "user.name", "Fixture")
        cls.git("config", "user.email", "fixture@example.invalid")
        cls.git("config", "core.autocrlf", "false")
        (cls.root / "app.py").write_text("value = 1\n", newline="\n")
        cls.git("add", ".")
        cls.git("commit", "-m", "base")
        cls.base = cls.git("rev-parse", "HEAD")
        (cls.root / "app.py").write_text("value = 2\n", newline="\n")
        cls.git("add", ".")
        cls.git("commit", "-m", "code")
        cls.first = cls.git("rev-parse", "HEAD")
        (cls.root / "test_app.py").write_text("assert True\n")
        (cls.root / "README.md").write_text("description\n")
        (cls.root / "image.png").write_bytes(b"\x00\xff\x01")
        cls.git("add", ".")
        cls.git("commit", "-m", "test and docs")
        cls.second = cls.git("rev-parse", "HEAD")
        (cls.root / "app.py").write_text("value = 3\n", newline="\n")
        cls.git("add", ".")
        cls.git("commit", "-m", "code again")
        cls.third = cls.git("rev-parse", "HEAD")

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def setUp(self):
        self.output_temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.output_temp.cleanup)
        self.output = Path(self.output_temp.name)
        self.provider = FakeProvider()
        self.clock = patch.object(review, "beijing_now", return_value=review.datetime(2026, 9, 10, 20, tzinfo=review.BEIJING))
        self.clock.start()
        self.addCleanup(self.clock.stop)

    def run_at(self, head, previous=None, rules="rules", force=False, base=None):
        revision = {"base": base or self.base, "head": head, "base_ref": "main"}
        github = FakeGitHub(revision)
        self.last_github = github
        tools = review.RepositoryTools(self.root, revision, revision["base"], review.Budget(), review.git)
        data, _ = core_diff(self.root, revision["base"], head, tools.budget, review.git)
        # Give each run its own artifact directory, as Actions does in production.
        out = self.output / str(len(list(self.output.iterdir())))
        out.mkdir()
        result = review.scoped_review(github, lambda: self.provider, revision, "Title", "fixture", rules,
                    revision["base"], data, out, tools, previous, force)
        state = json.loads((out / "session.json").read_bytes()) if (out / "session.json").exists() else None
        return result, state, github, json.loads((out / "manifest.json").read_bytes())

    def test_scope_keeps_runtime_and_excludes_tests_even_when_mixed_with_binary_assets(self):
        for path in ["src/main/java/App.java", "src/main/java/Contest.java", "frontend/app/page.tsx", "executor-service/worker.py",
                     "deploy/compose.yaml", "build.gradle.kts", "gradle/wrapper/gradle-wrapper.jar",
                     ".github/workflows/ci.yml", "frontend/package-lock.json", ".agents/skills/x/run.py"]:
            self.assertTrue(is_core(path), path)
        for path in ["src/test/java/AppTests.java", "src/integrationTest/resources/db.yaml",
                     "frontend/tests/page.test.tsx", "executor-service/test_worker.py", "deploy/tests/lib.sh",
                     "executor-service/Dockerfile.tests", "executor-native/probe.py", "docs/usage.md",
                     "AGENTS.md", ".agents/skills/x/SKILL.md", "executor-service/native_probe.py", "acceptance/seed.py"]:
            self.assertFalse(is_core(path), path)
        _, state, _, _ = self.run_at(self.second)
        body = json.dumps(state["requests"], ensure_ascii=False)
        self.assertIn("value = 2", body)
        self.assertNotIn("assert True", body)
        self.assertNotIn("Binary files", body)

    def test_test_only_push_never_constructs_provider_or_overwrites_reviewed_head(self):
        _, first, _, _ = self.run_at(self.first)
        calls = len(self.provider.requests)
        result, second, github, manifest = self.run_at(self.second, first)
        self.assertEqual(calls, len(self.provider.requests))
        self.assertEqual(first, second)
        self.assertEqual([], github.posts)
        self.assertEqual("exempt", manifest["state"])
        self.assertIn("previous findings are unchanged", result)

    def test_new_test_only_pr_is_exempt_without_a_provider_key(self):
        result, state, github, manifest = self.run_at(self.second, base=self.first)
        self.assertEqual([], self.provider.requests)
        self.assertIsNone(state)
        self.assertEqual([], github.posts)
        self.assertEqual("exempt", manifest["state"])

    def test_scope_crossing_rename_preserves_the_runtime_deletion(self):
        import subprocess
        tree = self.output / "move.git"
        subprocess.run(["git", "clone", "--bare", str(self.root), str(tree)], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        def git(*args, data=None):
            return subprocess.check_output(["git", *args], cwd=tree, input=data, stderr=subprocess.DEVNULL)
        oid = git("rev-parse", self.first + ":app.py").decode().strip()
        # A root test_ path exercises both sides without platform-dependent filesystem renames.
        changed_tree = git("mktree", data=f"100644 blob {oid}\ttest_app.py\n".encode()).decode().strip()
        commit = subprocess.check_output(["git", "-c", "user.name=Fixture", "-c", "user.email=f@example.invalid",
                        "commit-tree", changed_tree, "-p", self.first], cwd=tree, input=b"move\n").decode().strip()
        data, paths = core_diff(tree, self.first, commit, review.Budget(), review.git)
        self.assertEqual(["app.py"], paths)
        self.assertIn(b"deleted file mode", data)
        self.assertNotIn(b"test_app.py", data)

    def test_reasoning_and_tool_messages_survive_between_review_runs(self):
        class Provider(FakeProvider):
            def complete(self, body, record=None):
                self.requests.append(body)
                usage = {"prompt_tokens": 1000, "completion_tokens": 20}
                if len(self.requests) == 1:
                    return {"role": "assistant", "content": "", "reasoning_content": "fixture reasoning before read",
                            "tool_calls": [{"id": "read1", "type": "function", "function": {"name": "repository",
                             "arguments": json.dumps({"action": "read", "revision": "head", "path": "app.py"})}}]}, usage
                return {"role": "assistant", "content": "fixture finding", "reasoning_content": "fixture final reasoning"}, usage
        self.provider = Provider()
        _, first, _, _ = self.run_at(self.first)
        _, _, _, _ = self.run_at(self.third, first)
        sent = json.loads(self.provider.requests[-1])
        old = first["requests"]["cross-contract"]["messages"]
        self.assertEqual(old, sent["messages"][:len(old)])
        self.assertTrue(any(m.get("reasoning_content") == "fixture reasoning before read" for m in old))
        self.assertTrue(any(m["role"] == "tool" and self.first in m["content"] for m in old))

    def test_resume_keeps_prefix_and_appends_changes_since_last_actual_review(self):
        _, first, _, _ = self.run_at(self.first)
        _, skipped, _, _ = self.run_at(self.second, first)
        _, third, _, manifest = self.run_at(self.third, skipped)
        sent = json.loads(self.provider.requests[-1])
        old = first["requests"]["cross-contract"]["messages"]
        self.assertEqual(old, sent["messages"][:len(old)])
        self.assertIn("-value = 2\n+value = 3", sent["messages"][len(old)]["content"])
        self.assertIn(self.third, sent["messages"][len(old)]["content"])
        self.assertEqual("continued", manifest["session_mode"])
        self.assertEqual(2, len(third["reports"]))
        self.assertEqual(self.first, third["reports"][0]["revision"]["head"])

    def test_rules_change_rebuilds_current_full_diff_and_keeps_prior_findings(self):
        _, first, _, _ = self.run_at(self.first)
        _, _, _, manifest = self.run_at(self.third, first, rules="new rules")
        sent = json.loads(self.provider.requests[-1])
        self.assertIn("new rules", sent["messages"][0]["content"])
        self.assertIn("-value = 1\n+value = 3", sent["messages"][1]["content"])
        self.assertIn("Fixture review", sent["messages"][1]["content"])
        self.assertEqual("checkpoint", manifest["session_mode"])

    def test_large_conversation_compacts_to_versioned_reports_and_read_locations(self):
        _, first, _, _ = self.run_at(self.first)
        first["requests"]["cross-contract"]["messages"].insert(1, {"role": "user", "content": "x" * 300_000})
        first["context_tokens"]["cross-contract"] = review.COMPACT_TOKENS
        first["reports"][0]["reads"] = [{"action": "read", "revision": "head", "path": "app.py", "offset": 0}]
        _, _, _, manifest = self.run_at(self.third, first)
        sent = json.loads(self.provider.requests[-1])
        self.assertNotIn("x" * 1000, json.dumps(sent))
        self.assertIn("Fixture review", sent["messages"][1]["content"])
        self.assertIn("app.py", sent["messages"][1]["content"])
        self.assertEqual("checkpoint", manifest["session_mode"])

    def test_large_cached_transcript_uses_measured_tokens_instead_of_its_byte_size(self):
        _, first, _, _ = self.run_at(self.first)
        first["requests"]["cross-contract"]["messages"].insert(1, {"role": "user", "content": "x" * 4_100_000})
        first["context_tokens"]["cross-contract"] = 550_000
        _, _, _, manifest = self.run_at(self.third, first)
        self.assertEqual("continued", manifest["session_mode"])
        self.assertIn("x" * 4_100_000, self.provider.requests[-1].decode("utf-8"))

    def test_base_change_does_not_reuse_old_context(self):
        _, first, _, _ = self.run_at(self.first)
        _, _, _, manifest = self.run_at(self.third, first, base=self.first)
        self.assertEqual("checkpoint", manifest["session_mode"])

    def test_tools_return_current_commit_and_stale_publication_is_rejected(self):
        revision = {"base": self.base, "head": self.third, "base_ref": "main"}
        tools = review.RepositoryTools(self.root, revision, self.base, review.Budget(), review.git)
        result = json.loads(tools.call("repository", {"action": "read", "revision": "head", "path": "app.py"}))
        self.assertEqual(self.third, result["commit"])
        self.assertIn("value = 3", result["lines"][0]["text"])

    def test_checkpoint_never_silently_drops_older_findings(self):
        state = {"reports": [{"body": "猫" * 30_000, "revision": i} for i in range(4)], "dropped_reports": 3}
        raw = review_session.checkpoint(state, review.encoded)
        result = json.loads(raw)
        self.assertLessEqual(len(raw.encode("utf-8")), review_session.HISTORY_BYTES)
        self.assertEqual([3], [report["revision"] for report in result["reports"]])
        self.assertEqual(6, result["dropped_reports"])
        oversized = json.loads(review_session.checkpoint({"reports": ["x" * 180_000]}, review.encoded))
        self.assertEqual({"reports": [], "dropped_reports": 1}, oversized)

    def test_checkpoint_count_and_byte_limits_are_independent(self):
        result = json.loads(review_session.checkpoint({"reports": list(range(12))}, review.encoded))
        self.assertEqual(list(range(4, 12)), result["reports"])
        self.assertEqual(4, result["dropped_reports"])

    def test_overgrown_history_can_be_reviewed_automatically_and_by_dispatch(self):
        _, first, _, _ = self.run_at(self.first)
        first["reports"] = [{"body": "猫" * 50_000, "parts": ["猫" * 50_000]} for _ in range(3)]
        for force in [False, True]:
            with self.subTest(force=force):
                _, state, github, manifest = self.run_at(self.third, first, force=force)
                self.assertEqual("complete", manifest["state"])
                self.assertEqual(1, len(github.posts))
                self.assertEqual(3, state["dropped_reports"])
                self.assertEqual(1, len(state["reports"]))

    def test_retention_fallback_is_prepared_before_posting(self):
        original = FakeGitHub.post
        def post(github, head, body):
            pending = next(self.output.glob("*/session.pending.json"))
            raw = pending.read_bytes()
            self.assertLessEqual(len(raw), 5000)
            self.assertEqual({}, json.loads(raw)["requests"])
            self.assertEqual("incomplete", json.loads((pending.parent / "manifest.json").read_bytes())["state"])
            return original(github, head, body)
        with patch.object(review_session, "SESSION_BYTES", 5000), patch.object(FakeGitHub, "post", post):
            _, _, github, manifest = self.run_at(self.first)
        self.assertEqual(1, len(github.posts))
        self.assertEqual("complete", manifest["state"])

    def test_storage_bound_failure_cannot_follow_a_posted_review(self):
        with patch.object(review_session, "SESSION_BYTES", 1), self.assertRaises(ValueError):
            self.run_at(self.first)
        self.assertEqual([], self.last_github.posts)
        manifest = json.loads(next(self.output.glob("*/manifest.json")).read_bytes())
        self.assertEqual("incomplete", manifest["state"])

    def test_restore_only_uses_matching_trusted_run_artifacts_and_checks_identity(self):
        _, state, _, _ = self.run_at(self.first)
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            z.writestr("session.json", review.encoded(state))
            z.writestr("../ignored", "never extracted")
        class GitHub:
            repository, number, budget = "owner/project", "25", review.Budget()
            def api(self, path):
                if "workflows/" in path:
                    return {"workflow_runs": [{"id": 3, "event": "pull_request", "head_branch": "feature"},
                                              {"id": 2, "event": "workflow_run", "head_branch": "main",
                                               "display_title": "AI Review PR #25"}]}
                self.assert_path = path
                return {"artifacts": [{"id": 4, "name": "ai-review-session-26-2-1", "expired": False},
                                      {"id": 5, "name": "ai-review-session-25-2-1", "expired": False},
                                      {"id": 6, "name": "ai-review-25-2-1", "expired": False}]}
        calls = []
        def download(args, budget, **kwargs):
            calls.append(args)
            return buf.getvalue()
        restored = review_session.restore(GitHub(), download, "9")
        self.assertEqual(state, restored)
        self.assertEqual(1, len(calls))
        self.assertIn("artifacts/5/zip", calls[0][-1])
        with self.assertRaises(ValueError):
            review_session.decode(review.encoded(state), "owner/project", "26")


if __name__ == "__main__":
    unittest.main()
