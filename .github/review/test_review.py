import copy
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch
import urllib.error

import review


class FakeGitHub:
    repository = "owner/project"

    def __init__(self, revision):
        self.revision = revision
        self.posts = []
        self.reads = 0
        self.drift_after = None

    def current(self):
        self.reads += 1
        head = self.revision["head"]
        if self.drift_after and self.reads >= self.drift_after:
            head = "f" * 40
        return {"state": "open", "draft": False, "author_association": "OWNER", "title": "fixture",
                "number": 25, "base": {"sha": self.revision["base"], "ref": "main",
                "repo": {"full_name": self.repository}}, "head": {"sha": head}}

    def post(self, head, body):
        self.posts.append({"commit_id": head, "body": body})
        return {"id": len(self.posts)}


class FakeProvider:
    def __init__(self):
        self.requests = []
        self.fail_at = None

    def review(self, body):
        self.requests.append(body)
        if self.fail_at == len(self.requests):
            raise review.Incomplete("Fixture provider failure.")
        return "Fixture review: inspect the caller and consumer together. @literal"


class ReviewTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = tempfile.TemporaryDirectory()
        cls.repo = Path(cls.fixture.name) / "source"
        cls.repo.mkdir()

        def git(*args):
            return subprocess.check_output(["git", *args], cwd=cls.repo, stderr=subprocess.DEVNULL).decode().strip()

        git("init", "-b", "main")
        git("config", "user.email", "fixture@example.invalid")
        git("config", "user.name", "Fixture")
        git("config", "core.autocrlf", "false")
        (cls.repo / "base.txt").write_text("base\n", encoding="utf-8")
        git("add", ".")
        git("commit", "-m", "fixture base")
        cls.base = git("rev-parse", "HEAD")
        (cls.repo / "large.txt").write_text("".join(f"第{i}行：原始内容与百分号%引号\"\n" for i in range(25001)),
                                           encoding="utf-8", newline="\n")
        (cls.repo / ".github/review").mkdir(parents=True)
        (cls.repo / ".github/review/review.py").write_text("raise RuntimeError('MALICIOUS HEAD EXECUTED')\n")
        (cls.repo / ".gitattributes").write_text("*.txt diff=evil\n")
        (cls.repo / ".gitmodules").write_text('[submodule "evil"]\npath=evil\nurl=https://example.invalid/evil\n')
        cls.marker = cls.repo / "executed"
        (cls.repo / "evil.py").write_text("from pathlib import Path\nPath('executed').touch()\n")
        git("config", "diff.evil.command", "python evil.py")
        git("config", "diff.evil.textconv", "python evil.py")
        (cls.repo / ".git/hooks/post-checkout").write_text("#!/bin/sh\necho bad > executed\n")
        git("add", ".")
        git("commit", "-m", "fixture head")
        cls.head = git("rev-parse", "HEAD")
        cls.revision = {"base": cls.base, "head": cls.head, "base_ref": "main"}
        cls.merge, cls.data = review.object_diff(cls.repo, cls.revision, review.Budget())

    @classmethod
    def tearDownClass(cls):
        cls.fixture.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.output = Path(self.temp.name)
        self.github = FakeGitHub(self.revision)
        self.provider = FakeProvider()

    def run_review(self, data=None):
        review.complete_review(self.github, self.provider, self.revision, "中文\"标题", "fixture-model",
                               "trusted rules", self.merge, self.data if data is None else data, self.output)

    def test_real_git_large_diff_preserves_every_byte_with_utf8_and_request_wrappers(self):
        self.assertGreater(self.data.count(b"\n"), 20000)
        self.assertGreater((self.repo / "large.txt").stat().st_size, 200000)
        make = lambda text: review.payload("fixture", "规则" * 1000, self.revision, '"' * 90, text)
        parts = review.split_diff(self.data, make, cap=100000)
        self.assertGreater(len(parts), 2)
        self.assertEqual(self.data, b"".join(part["raw"] for part in parts))
        offset = 0
        for part in parts:
            self.assertEqual(offset, part["start"])
            offset = part["end"]
            self.assertEqual(part["sha256"], review.digest(part["raw"]))
            self.assertLessEqual(len(part["request"]), 100000)
            parsed = json.loads(part["request"])
            self.assertIn(part["raw"].decode("utf-8"), parsed["messages"][1]["content"])
        self.assertEqual(len(self.data), offset)
        self.assertEqual([p["sha256"] for p in parts],
                         [p["sha256"] for p in review.split_diff(self.data, make, cap=100000)])

    def test_diff_does_not_execute_head_hooks_textconv_or_external_diff(self):
        self.assertFalse(self.marker.exists())
        self.assertIn(b"MALICIOUS HEAD EXECUTED", self.data)
        self.assertIn(b".gitmodules", self.data)
        self.assertIn(b"diff=evil", self.data)
        self.assertEqual(self.base, self.merge)

    def test_bare_git_object_read_never_materializes_head_code(self):
        bare = self.output / "objects.git"
        subprocess.run(["git", "clone", "--bare", "--no-local", str(self.repo), str(bare)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        merge, data = review.object_diff(bare, self.revision, review.Budget())
        self.assertEqual(self.merge, merge)
        self.assertEqual(self.data, data)
        self.assertFalse((bare / ".github").exists())
        self.assertFalse((bare / "executed").exists())

    def test_fetch_accepts_only_fixed_repository_and_commit_objects(self):
        calls = []

        def fake_git(args, budget, directory, limit=review.DIFF_BYTES):
            calls.append(args)
            return self.base.encode() if args[0] == "merge-base" else self.data

        with patch.object(review, "git", side_effect=fake_git):
            review.fetch_diff(self.output, self.revision, self.github, review.Budget())
        self.assertEqual(["init", "--bare", "."], calls[0])
        self.assertEqual(["fetch", "--no-tags", "--no-recurse-submodules", "https://github.com/owner/project.git",
                          self.base, self.head], calls[1])
        self.assertIn("--no-ext-diff", calls[-1])
        self.assertIn("--no-textconv", calls[-1])

    def test_impossible_utf8_line_is_explicitly_incomplete(self):
        data = b"diff --git a/huge b/huge\n@@ -0,0 +1 @@\n+" + "猫".encode() * 100000 + b"\n"
        with self.assertRaisesRegex(review.Incomplete, "One UTF-8 diff line"):
            self.run_review(data)
        self.assertEqual([], self.provider.requests)
        self.assertEqual("incomplete", json.loads((self.output / "manifest.json").read_bytes())["state"])

    def test_whole_request_not_only_diff_is_bounded(self):
        with self.assertRaisesRegex(review.Incomplete, "One UTF-8 diff line"):
            review.split_diff(b"+x\n", lambda text: review.payload("model", "x" * 200000,
                                                               self.revision, "title", text))

    def test_parts_and_cross_contract_results_retained_and_posts_bound_to_head(self):
        self.run_review()
        manifest = json.loads((self.output / "manifest.json").read_bytes())
        self.assertEqual("complete", manifest["state"])
        self.assertEqual(len(manifest["parts"]) + 1, len(self.provider.requests))
        self.assertEqual(len(self.provider.requests), len(self.github.posts))
        retained = b"".join((self.output / f"part-{part['part']:02d}.diff").read_bytes()
                            for part in manifest["parts"])
        self.assertEqual(self.data, retained)
        for part in manifest["parts"]:
            raw_review = (self.output / f"part-{part['part']:02d}.md").read_bytes()
            self.assertEqual(part["review_sha256"], review.digest(raw_review))
            self.assertIn(b"@literal", raw_review)
        self.assertTrue((self.output / "cross-contract.md").exists())
        for post in self.github.posts:
            self.assertEqual(self.head, post["commit_id"])
            self.assertNotIn("@literal", post["body"])
        self.assertIn("complete coverage", self.github.posts[-1]["body"])

    def test_missing_part_never_posts_completion_and_retains_prior_results(self):
        self.provider.fail_at = 2
        with self.assertRaisesRegex(review.Incomplete, "Fixture provider failure"):
            self.run_review()
        manifest = json.loads((self.output / "manifest.json").read_bytes())
        self.assertEqual("incomplete", manifest["state"])
        self.assertEqual("pending", manifest["parts"][1]["state"])
        self.assertTrue((self.output / "part-01.md").exists())
        self.assertEqual(1, len(self.github.posts))
        self.assertNotIn("complete coverage", self.github.posts[0]["body"])

    def test_head_drift_after_provider_before_post_blocks_stale_review(self):
        self.github.drift_after = 2
        with self.assertRaisesRegex(review.Incomplete, "stale and incomplete"):
            self.run_review()
        self.assertEqual([], self.github.posts)
        self.assertTrue((self.output / "part-01.md").exists())

    def test_base_drift_is_also_rejected(self):
        current = self.github.current

        def moved():
            result = current()
            result["base"]["sha"] = "a" * 40
            return result

        self.github.current = moved
        with self.assertRaisesRegex(review.Incomplete, "stale and incomplete"):
            self.run_review()
        self.assertEqual([], self.provider.requests)

    def test_entrypoint_failure_has_nonzero_status_manifest_and_summary(self):
        event_path = self.output / "event.json"
        event_path.write_text(json.dumps({"inputs": {"pr_number": "25"}}))
        self.provider.fail_at = 1
        env = {"REVIEW_OUTPUT": str(self.output), "GITHUB_EVENT_PATH": str(event_path),
               "GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_REF": "refs/heads/main",
               "GITHUB_REPOSITORY": self.github.repository, "GITHUB_STEP_SUMMARY": str(self.output / "summary")}
        with patch.dict(os.environ, env), patch.object(review, "GitHub", return_value=self.github), \
                patch.object(review, "Provider", return_value=self.provider), \
                patch.object(review, "fetch_diff", return_value=(self.merge, self.data)):
            self.assertEqual(1, review.main())
        self.assertIn("INCOMPLETE", (self.output / "summary").read_text())
        self.assertEqual("incomplete", json.loads((self.output / "manifest.json").read_bytes())["state"])

    def test_manual_dispatch_cannot_run_feature_branch_code(self):
        event = self.output / "event.json"
        event.write_text('{"inputs":{"pr_number":"25"}}')
        env = {"REVIEW_OUTPUT": str(self.output), "GITHUB_EVENT_PATH": str(event),
               "GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_REF": "refs/heads/codex/phase-one-assets"}
        with patch.dict(os.environ, env), patch.object(review, "Provider") as provider:
            self.assertEqual(1, review.main())
            provider.assert_not_called()

    def test_missing_key_and_expired_budget_are_explicit_failures(self):
        with self.assertRaisesRegex(review.Incomplete, "missing"):
            review.Provider("https://example.invalid", "", review.Budget())
        budget = review.Budget()
        budget.deadline = 0
        with self.assertRaisesRegex(review.Incomplete, "time budget"):
            budget.timeout(300)

    @unittest.skipUnless(hasattr(review.signal, "SIGALRM"), "Production wall deadline runs on Linux")
    def test_wall_deadline_interrupts_a_slow_provider_and_records_failure(self):
        event = self.output / "event.json"
        event.write_text('{"inputs":{"pr_number":"25"}}')
        env = {"REVIEW_OUTPUT": str(self.output), "GITHUB_EVENT_PATH": str(event),
               "GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_REF": "refs/heads/main",
               "GITHUB_REPOSITORY": self.github.repository}
        self.provider.review = lambda body: time.sleep(10)
        small = b"diff --git a/a b/a\n@@ -0,0 +1 @@\n+new\n"
        start = time.monotonic()
        with patch.dict(os.environ, env), patch.object(review, "GitHub", return_value=self.github), \
                patch.object(review, "Provider", return_value=self.provider), \
                patch.object(review, "fetch_diff", return_value=(self.merge, small)), \
                patch.object(review, "RUN_SECONDS", 0.1):
            self.assertEqual(1, review.main())
        self.assertLess(time.monotonic() - start, 2)
        manifest = json.loads((self.output / "manifest.json").read_bytes())
        self.assertEqual("incomplete", manifest["state"])
        self.assertIn("time budget expired", manifest["reason"])
        self.assertEqual([], self.github.posts)

    def test_too_many_parts_fail_before_any_provider_call(self):
        with patch.object(review, "MAX_PARTS", 1):
            with self.assertRaisesRegex(review.Incomplete, "32 bounded review parts"):
                self.run_review()
        self.assertEqual([], self.provider.requests)

    def test_provider_never_called_when_cross_contract_context_is_too_large(self):
        self.provider.review = lambda body: "x" * 49000
        with self.assertRaisesRegex(review.Incomplete, "Cross-contract review exceeds"):
            self.run_review()
        self.assertFalse((self.output / "cross-contract.md").exists())
        self.assertNotIn("complete coverage", self.github.posts[-1]["body"])

    def test_workflow_keeps_main_trust_and_no_optional_failure(self):
        root = Path(__file__).resolve().parents[2]
        workflow = (root / ".github/workflows/ai-review.yml").read_text(encoding="utf-8")
        self.assertIn("ref: main", workflow)
        self.assertIn("persist-credentials: false", workflow)
        self.assertIn("github.event.changes.base != null", workflow)
        self.assertIn("github.ref == 'refs/heads/main'", workflow)
        self.assertNotIn("continue-on-error", workflow)
        self.assertNotIn("application/vnd.github.v3.diff", workflow)
        self.assertNotIn("ref: ${{ github.event.pull_request.head", workflow)
        ci = (root / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        self.assertIn('unittest discover -s .github/review -p "test_*.py"', ci)

    def test_identity_rejects_non_owner_and_accepts_explicit_stack(self):
        pr = self.github.current()
        pr["base"]["ref"] = "codex/phase-one-assets"
        self.assertEqual("codex/phase-one-assets", review.identity(pr, self.github.repository)["base_ref"])
        for field, value in [("author_association", "CONTRIBUTOR"), ("draft", True), ("state", "closed")]:
            bad = copy.deepcopy(pr)
            bad[field] = value
            with self.assertRaises(review.Incomplete):
                review.identity(bad, self.github.repository)

    def test_provider_refuses_empty_truncated_or_oversized_response(self):
        provider = review.Provider("https://example.invalid", "fixture", review.Budget())
        for content, finish in [("", "stop"), ("partial", "length"), (None, "stop"), ("x" * 50001, "stop")]:
            body = review.encoded({"choices": [{"message": {"content": content}, "finish_reason": finish}]})
            with patch.object(provider.opener, "open", return_value=io.BytesIO(body)):
                with self.assertRaises(review.Incomplete):
                    provider.review(b"{}")
        with patch.object(provider.opener, "open", side_effect=urllib.error.URLError("fixture")):
            with self.assertRaisesRegex(review.Incomplete, "endpoint failed"):
                provider.review(b"{}")

    def test_provider_uses_bounded_request_and_does_not_follow_redirects(self):
        provider = review.Provider("https://example.invalid", "fixture", review.Budget())
        raw = review.encoded({"choices": [{"message": {"content": "complete"}, "finish_reason": "stop"}]})
        with patch.object(provider.opener, "open", return_value=io.BytesIO(raw)) as opener:
            self.assertEqual("complete", provider.review(b"{}"))
            self.assertEqual("Bearer fixture", opener.call_args.args[0].get_header("Authorization"))
            self.assertLessEqual(opener.call_args.kwargs["timeout"], 300)
        with self.assertRaises(review.Incomplete):
            review.NoRedirect().redirect_request(None, None, 302, "", {}, "https://other.invalid")
        with self.assertRaises(review.Incomplete):
            provider.review(b"x" * (review.REQUEST_BYTES + 1))


if __name__ == "__main__":
    unittest.main()
