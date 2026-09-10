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
    number = "25"

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
                "repo": {"full_name": self.repository}}, "head": {"sha": head, "ref": "fixture-branch"}}

    def post(self, head, body):
        self.posts.append({"commit_id": head, "body": body})
        return {"id": len(self.posts)}


class FakeProvider:
    def __init__(self):
        self.requests = []
        self.fail_at = None

    def complete(self, body, record=None):
        self.requests.append(body)
        if self.fail_at == len(self.requests):
            raise review.Incomplete("Fixture provider failure.")
        return {"role": "assistant", "content": "Fixture review: inspect the caller and consumer together. @literal"}, {"prompt_tokens": 1000, "completion_tokens": 10}


def final_request(request):
    last = request["messages"][-1]
    return last == review.FINAL_MESSAGE or (last["role"] == "tool" and review.FINAL_NOTICE in last["content"])


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
        self.now = review.datetime(2026, 9, 9, 20, tzinfo=review.BEIJING)
        clock = patch.object(review, "beijing_now", side_effect=lambda: self.now)
        clock.start()
        self.addCleanup(clock.stop)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.output = Path(self.temp.name)
        self.github = FakeGitHub(self.revision)
        self.provider = FakeProvider()

    def run_review(self, data=None):
        review.complete_review(self.github, self.provider, self.revision, "中文\"标题", "fixture-model",
                               "trusted rules", self.merge, self.data if data is None else data, self.output,
                               review.RepositoryTools(self.repo, self.revision, self.merge, review.Budget(), review.git))

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
            review.split_diff(b"+x\n", lambda text: review.payload("model", "x" * review.REQUEST_BYTES,
                                                               self.revision, "title", text))

    def test_parts_and_cross_contract_results_retained_and_posts_bound_to_head(self):
        self.run_review()
        manifest = json.loads((self.output / "manifest.json").read_bytes())
        self.assertEqual("complete", manifest["state"])
        self.assertEqual(len(manifest["parts"]) + 1, len(self.provider.requests))
        self.assertEqual(1, len(self.github.posts))
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
        self.assertEqual((self.output / "cross-contract.md").read_text(encoding="utf-8").replace("@", "＠"),
                         self.github.posts[-1]["body"])

    def test_missing_part_never_posts_completion_and_retains_prior_results(self):
        self.provider.fail_at = 2
        with self.assertRaisesRegex(review.Incomplete, "Fixture provider failure"):
            self.run_review()
        manifest = json.loads((self.output / "manifest.json").read_bytes())
        self.assertEqual("incomplete", manifest["state"])
        self.assertEqual("pending", manifest["parts"][1]["state"])
        self.assertTrue((self.output / "part-01.md").exists())
        self.assertEqual([], self.github.posts)

    def test_entire_pr_shares_scaled_turn_budget_and_single_part_runs_one_loop(self):
        for changed_files in (1, 12):
            self.provider = FakeProvider()
            self.github = FakeGitHub(self.revision)

            def complete(body, record=None):
                self.provider.requests.append(body)
                request = json.loads(body)
                if final_request(request):
                    self.assertNotIn("tool_choice", request)
                    return {"role": "assistant", "content": "Final observed findings."}, {
                        "prompt_tokens": 1000, "completion_tokens": 10}
                return {"role": "assistant", "content": "", "tool_calls": [{
                    "id": "call_" + str(len(self.provider.requests)), "type": "function", "function": {
                        "name": "repository", "arguments": json.dumps({"action":"read", "revision":"head", "path":"large.txt", "offset":len(self.provider.requests), "limit":1})}}]}, {
                            "prompt_tokens": 1000, "completion_tokens": 10}

            self.provider.complete = complete
            data = b"".join(f"diff --git a/file{i} b/file{i}\n@@ -0,0 +1 @@\n+new\n".encode()
                            for i in range(changed_files))
            self.run_review(data)
            manifest = json.loads((self.output / "manifest.json").read_bytes())
            expected = min(30, 8 + 2 * changed_files)
            self.assertEqual(expected, len(self.provider.requests))
            self.assertEqual(expected, manifest["used_turns"])
            self.assertEqual(expected, manifest["max_turns"])
            self.assertEqual("complete", manifest["state"])
            self.assertEqual(1, len(self.github.posts))
            # One part: the whole budget belongs to one loop and nothing is re-read from scratch.
            self.assertEqual(1, len(manifest["parts"]))
            self.assertEqual("cross-contract", manifest["parts"][0]["review_stage"])
            self.assertEqual((self.output / "cross-contract.md").read_bytes(), (self.output / "part-01.md").read_bytes())
            self.assertEqual(self.github.posts[0]["body"], (self.output / "part-01.md").read_text(encoding="utf-8"))

    def use_loop_provider(self, change_time=None):
        def complete(body, record=None):
            self.provider.requests.append(body)
            number = len(self.provider.requests)
            if change_time:
                change_time(number)
            request = json.loads(body)
            usage = {"prompt_tokens": 1000, "completion_tokens": 10}
            if final_request(request):
                return {"role": "assistant", "content": f"Final observed findings {number}."}, usage
            return {"role": "assistant", "content": "", "tool_calls": [{
                "id": f"read_{number}", "type": "function", "function": {
                    "name": "repository", "arguments": json.dumps({"action": "read", "revision": "head",
                    "path": "large.txt", "offset": number, "limit": 1})}}]}, usage
        self.provider.complete = complete

    def test_peak_small_pr_uses_three_total_calls_with_budget_hints_on_tool_results(self):
        self.now = self.now.replace(hour=16)
        self.use_loop_provider()
        data = b"diff --git a/base.txt b/base.txt\n@@ -1 +1 @@\n-base\n+changed\n"
        self.run_review(data)
        requests = [json.loads(body) for body in self.provider.requests]
        self.assertEqual(3, len(requests))
        self.assertIn(data.decode(), requests[0]["messages"][1]["content"])
        for index, request in enumerate(requests):
            last = request["messages"][-1]
            self.assertEqual("user" if index == 0 else "tool", last["role"])
            hint = last["content"] if index == 0 else json.loads(last["content"])["budget"]
            self.assertIn(f"本阶段剩余最多 {3 - index} 轮", hint)
            self.assertIn("峰价", hint)
            if index:
                prefix = requests[index - 1]["messages"]
                self.assertEqual(prefix, request["messages"][:len(prefix)])
        self.assertNotIn("notice", json.loads(requests[1]["messages"][-1]["content"]))
        self.assertEqual(review.FINAL_NOTICE, json.loads(requests[2]["messages"][-1]["content"])["notice"])
        self.assertNotIn("tool_choice", requests[-1])
        self.assertEqual([{"commit_id": self.head, "body": "Final observed findings 3."}], self.github.posts)
        manifest = json.loads((self.output / "manifest.json").read_bytes())
        self.assertEqual(3, manifest["max_turns"])
        self.assertEqual(3, manifest["used_turns"])
        self.assertEqual("complete", manifest["state"])
        self.assertEqual("cross-contract", manifest["parts"][0]["review_stage"])
        self.assertEqual((self.output / "cross-contract.md").read_bytes(), (self.output / "part-01.md").read_bytes())
        trace = [json.loads(line) for line in (self.output / "agent-trace.jsonl").read_text().split("\n") if line]
        budgets = [event for event in trace if event["event"] == "round_budget"]
        self.assertEqual([3, 2, 1], [event["remaining_turns"] for event in budgets])

    def test_peak_can_finish_early_and_large_diff_never_silently_loses_coverage(self):
        self.now = self.now.replace(hour=10)
        self.run_review(b"diff --git a/x b/x\n@@ -0,0 +1 @@\n+x\n")
        self.assertEqual(1, len(self.provider.requests))
        self.github.posts.clear()
        self.provider.requests.clear()
        with self.assertRaisesRegex(review.Incomplete, "more calls than the PR turn budget"):
            self.run_review()
        self.assertEqual([], self.provider.requests)
        self.assertEqual([], self.github.posts)
        self.assertEqual("incomplete", json.loads((self.output / "manifest.json").read_bytes())["state"])

    def test_peak_result_coverage_is_retained_when_head_changes_before_posting(self):
        self.now = self.now.replace(hour=10)
        self.github.drift_after = 2
        with self.assertRaisesRegex(review.Incomplete, "stale and incomplete"):
            self.run_review(b"diff --git a/x b/x\n@@ -0,0 +1 @@\n+x\n")
        self.assertEqual([], self.github.posts)
        manifest = json.loads((self.output / "manifest.json").read_bytes())
        self.assertEqual("incomplete", manifest["state"])
        self.assertEqual(1, manifest["used_turns"])
        self.assertEqual("reviewed", manifest["parts"][0]["state"])
        self.assertEqual(review.digest((self.output / "part-01.md").read_bytes()),
                         manifest["parts"][0]["review_sha256"])

    def test_two_peak_diff_parts_reserve_the_third_call_for_the_only_posted_summary(self):
        self.now = self.now.replace(hour=10)
        self.use_loop_provider()
        data = b"".join(b"diff --git a/" + name + b" b/" + name + b"\n@@ -0,0 +1 @@\n"
                        + b"+fixture\n" * 22000 for name in (b"one", b"two"))
        self.run_review(data)
        self.assertEqual(3, len(self.provider.requests))
        # Each stage's first call is its last: the user instruction and tool_choice: none remain.
        self.assertTrue(all(json.loads(body)["tool_choice"] == "none" for body in self.provider.requests))
        self.assertTrue(all(json.loads(body)["messages"][-1] == review.FINAL_MESSAGE for body in self.provider.requests))
        self.assertEqual([{"commit_id": self.head, "body": "Final observed findings 3."}], self.github.posts)
        manifest = json.loads((self.output / "manifest.json").read_bytes())
        self.assertEqual(data, b"".join((self.output / f"part-{part['part']:02d}.diff").read_bytes()
                                       for part in manifest["parts"]))

    def test_entering_peak_mid_review_allows_only_three_more_calls_including_summary(self):
        self.now = self.now.replace(hour=13, minute=59)
        self.use_loop_provider(lambda number: setattr(self, "now", self.now.replace(hour=14)) if number == 5 else None)
        data = b"".join(f"diff --git a/file{i} b/file{i}\n@@ -0,0 +1 @@\n+x\n".encode() for i in range(12))
        self.run_review(data)
        self.assertEqual(8, len(self.provider.requests))
        manifest = json.loads((self.output / "manifest.json").read_bytes())
        self.assertEqual(8, manifest["max_turns"])
        self.assertEqual(8, manifest["used_turns"])
        self.assertEqual("complete", manifest["state"])
        self.assertEqual([{"commit_id": self.head, "body": "Final observed findings 8."}], self.github.posts)

    def test_leaving_peak_does_not_grow_the_running_review_budget(self):
        self.now = self.now.replace(hour=17, minute=59)
        self.use_loop_provider(lambda number: setattr(self, "now", self.now.replace(hour=18)))
        self.run_review(b"diff --git a/x b/x\n@@ -0,0 +1 @@\n+x\n")
        self.assertEqual(3, len(self.provider.requests))
        self.assertIn("谷价", json.loads(self.provider.requests[-1])["messages"][-1]["content"])

    def test_entering_peak_during_identity_check_does_not_spend_an_extra_peak_call(self):
        self.now = self.now.replace(hour=13, minute=59, second=59)
        original_current = self.github.current
        def current():
            result = original_current()
            if self.github.reads == 2:
                self.now = self.now.replace(hour=14, minute=0, second=0)
            return result
        self.github.current = current
        self.use_loop_provider()
        complete = self.provider.complete
        request_hours = []
        def recorded(body, record=None):
            request_hours.append(self.now.hour)
            return complete(body, record)
        self.provider.complete = recorded
        self.run_review(b"diff --git a/x b/x\n@@ -0,0 +1 @@\n+x\n")
        self.assertEqual([13, 14, 14, 14], request_hours)
        requests = [json.loads(body) for body in self.provider.requests]
        for previous, following in zip(requests, requests[1:]):
            prefix = previous["messages"]
            self.assertEqual(prefix, following["messages"][:len(prefix)])
        self.assertIn("剩余最多 3 轮", json.loads(requests[1]["messages"][-1]["content"])["budget"])
        self.assertEqual(review.FINAL_NOTICE, json.loads(requests[-1]["messages"][-1]["content"])["notice"])
        self.assertEqual(1, len(self.github.posts))

    def test_head_drift_after_provider_before_post_blocks_stale_review(self):
        self.github.drift_after = 2
        with self.assertRaisesRegex(review.Incomplete, "stale and incomplete"):
            self.run_review()
        self.assertEqual([], self.github.posts)
        self.assertEqual(1, len(self.provider.requests))
        self.assertTrue((self.output / "part-01-operations.json").exists())

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
               "GITHUB_REPOSITORY": self.github.repository, "GITHUB_RUN_ID": "1",
               "GITHUB_STEP_SUMMARY": str(self.output / "summary")}
        with patch.dict(os.environ, env), patch.object(review, "GitHub", return_value=self.github), \
                patch.object(review, "Provider", return_value=self.provider), \
                patch.object(review, "fetch_diff", return_value=(self.merge, self.data)), \
                patch.object(review.review_session, "restore", return_value=None), \
                patch.object(review, "verified_ci", return_value=True):
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
               "GITHUB_REPOSITORY": self.github.repository, "GITHUB_RUN_ID": "1"}
        self.provider.complete = lambda body, record=None: time.sleep(10)
        small = b"diff --git a/a b/a\n@@ -0,0 +1 @@\n+new\n"
        start = time.monotonic()
        with patch.dict(os.environ, env), patch.object(review, "GitHub", return_value=self.github), \
                patch.object(review, "Provider", return_value=self.provider), \
                patch.object(review, "fetch_diff", return_value=(self.merge, small)), \
                patch.object(review.review_session, "restore", return_value=None), \
                patch.object(review, "verified_ci", return_value=True), \
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
        self.provider.complete = lambda body, record=None: ({"role": "assistant", "content": "x" * 49000},
                                                       {"prompt_tokens": 1000, "completion_tokens": 100})
        with self.assertRaisesRegex(review.Incomplete, "Cross-contract review exceeds"):
            self.run_review()
        self.assertFalse((self.output / "cross-contract.md").exists())
        self.assertEqual([], self.github.posts)

    def test_workflow_keeps_main_trust_and_no_optional_failure(self):
        root = Path(__file__).resolve().parents[2]
        workflow = (root / ".github/workflows/ai-review.yml").read_text(encoding="utf-8")
        self.assertIn("ref: main", workflow)
        self.assertIn("persist-credentials: false", workflow)
        self.assertIn("workflows: [CI]", workflow)
        self.assertIn("github.event.workflow_run.conclusion == 'success'", workflow)
        self.assertNotIn("pull_request_target:", workflow)
        self.assertIn("github.ref == 'refs/heads/main'", workflow)
        self.assertNotIn("continue-on-error", workflow)
        self.assertNotIn("application/vnd.github.v3.diff", workflow)
        self.assertNotIn("ref: ${{ github.event.pull_request.head", workflow)
        ci = (root / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        self.assertIn("github.event.changes.base != null", ci)
        self.assertIn("github.event.changes.base == null && github.run_id || 'source'", ci)
        self.assertIn("'metadata-only' || 'verify'", ci)
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
            body = review.encoded({"choices": [{"message": {"role": "assistant", "content": content}, "finish_reason": finish}], "usage": {"prompt_tokens": 100, "completion_tokens": 10}})
            with patch.object(provider.opener, "open", return_value=io.BytesIO(body)):
                with self.assertRaises(review.Incomplete):
                    provider.complete(b"{}")
        with patch.object(provider.opener, "open", side_effect=urllib.error.URLError("fixture")):
            with self.assertRaisesRegex(review.Incomplete, "endpoint failed"):
                provider.complete(b"{}")

    def test_provider_uses_bounded_request_and_does_not_follow_redirects(self):
        provider = review.Provider("https://example.invalid", "fixture", review.Budget())
        raw = review.encoded({"choices": [{"message": {"role": "assistant", "content": "complete"}, "finish_reason": "stop"}],
                              "usage": {"prompt_tokens": 100, "completion_tokens": 10}})
        with patch.object(provider.opener, "open", return_value=io.BytesIO(raw)) as opener:
            self.assertEqual("complete", provider.complete(b"{}")[0]["content"])
            self.assertEqual("Bearer fixture", opener.call_args.args[0].get_header("Authorization"))
            self.assertLessEqual(opener.call_args.kwargs["timeout"], 900)
        with self.assertRaises(review.Incomplete):
            review.NoRedirect().redirect_request(None, None, 302, "", {}, "https://other.invalid")
        with self.assertRaises(review.Incomplete):
            provider.complete(b"x" * (review.TRANSPORT_BYTES + 1))


if __name__ == "__main__":
    unittest.main()
