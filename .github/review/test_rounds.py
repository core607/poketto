"""Re-review rounds: main moving under a reviewed change, author replies, and the findings audit."""

import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import review
from review_scope import core_diff
from test_review import FakeGitHub, FakeProvider


class MainMovesUnderReviewTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temp.name)

        def git(*args):
            return subprocess.check_output(["git", *args], cwd=cls.root, stderr=subprocess.DEVNULL).decode().strip()

        cls.git = staticmethod(git)
        git("init", "-b", "main")
        git("config", "user.name", "Fixture")
        git("config", "user.email", "fixture@example.invalid")
        git("config", "core.autocrlf", "false")

        def commit(path, text, message):
            (cls.root / path).write_text(text, newline="\n")
            git("add", ".")
            git("commit", "-m", message)
            return git("rev-parse", "HEAD")

        commit("lib.py", "shared = 1\n", "lib")
        cls.base = commit("app.py", "value = 1\n", "base")
        git("switch", "-c", "feature")
        cls.change = commit("app.py", "value = 2\n", "pr change")
        git("switch", "main")
        cls.main = commit("lib.py", "shared = 2\n", "main moves")
        git("switch", "feature")
        git("merge", "--no-edit", "main")
        cls.merged = git("rev-parse", "HEAD")
        cls.more = commit("app.py", "value = 3\n", "pr changes again")

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.output = Path(temp.name)
        self.provider = FakeProvider()
        clock = patch.object(review, "beijing_now", return_value=review.datetime(2026, 10, 13, 20, tzinfo=review.BEIJING))
        clock.start()
        self.addCleanup(clock.stop)

    def run_at(self, base, head, previous=None, replies=()):
        revision = {"base": base, "head": head, "base_ref": "main"}
        github = FakeGitHub(revision)
        github.replies = list(replies)
        merge = self.git("merge-base", base, head)
        tools = review.RepositoryTools(self.root, revision, merge, review.Budget(), review.git)
        data, _ = core_diff(self.root, merge, head, tools.budget, review.git)
        out = self.output / str(len(list(self.output.iterdir())))
        out.mkdir()
        result = review.scoped_review(github, lambda: self.provider, revision, "Title", "fixture", "rules",
                                      merge, data, out, tools, previous)
        state = json.loads((out / "session.json").read_bytes()) if (out / "session.json").exists() else None
        return result, state, github, json.loads((out / "manifest.json").read_bytes())

    def test_merging_main_without_changing_the_pr_keeps_the_review(self):
        _, first, _, _ = self.run_at(self.base, self.change)
        calls = len(self.provider.requests)
        result, state, github, manifest = self.run_at(self.main, self.merged, first)
        self.assertEqual(calls, len(self.provider.requests))
        self.assertIn("only main moved", result)
        self.assertEqual("exempt", manifest["state"])
        self.assertEqual([self.merged], [post["commit_id"] for post in github.posts])
        self.assertIn("只合并了 main", github.posts[0]["body"])
        self.assertIn("之前的审稿结论不变", github.posts[0]["body"])
        self.assertIn("only main moved", github.statuses[0]["description"])
        self.assertEqual(self.merged, github.statuses[0]["commit_id"])
        self.assertEqual(self.change, state["revision"]["head"])

    def test_a_new_change_after_main_moved_reviews_only_the_pr_against_the_new_base(self):
        _, first, _, _ = self.run_at(self.base, self.change)
        _, carried, _, _ = self.run_at(self.main, self.merged, first)
        _, _, _, manifest = self.run_at(self.main, self.more, carried)
        sent = json.loads(self.provider.requests[-1])
        content = sent["messages"][1]["content"]
        self.assertIn("-value = 1\n+value = 3", content)
        self.assertNotIn("shared = 2", content)
        self.assertEqual("checkpoint", manifest["session_mode"])

    def test_a_re_review_carries_the_authors_replies_as_untrusted_data(self):
        _, first, _, _ = self.run_at(self.base, self.change)
        replies = [{"at": "2026-10-13T01:00:00Z", "body": "B1 declined: the caller already checks this."}]
        self.run_at(self.main, self.more, first, replies)
        content = json.loads(self.provider.requests[-1])["messages"][1]["content"]
        self.assertIn("<untrusted-author-replies>", content)
        self.assertIn("B1 declined", content)

    def test_the_first_review_carries_no_replies(self):
        self.run_at(self.base, self.change, None, [{"at": "x", "body": "never sent"}])
        self.assertNotIn("never sent", self.provider.requests[-1].decode("utf-8"))


class ReplyBoundTests(unittest.TestCase):
    def test_only_the_newest_replies_that_fit_are_kept_in_order(self):
        replies = [{"at": str(i), "body": "猫" * 3000} for i in range(10)]
        block = review.replies_block(replies)
        data = json.loads(block.split("\n")[2])
        self.assertLessEqual(len(review.encoded(data["replies"])), review.REPLY_BYTES)
        self.assertEqual("9", data["replies"][-1]["at"])
        self.assertEqual(10 - len(data["replies"]), data["omitted_older"])
        self.assertEqual("", review.replies_block([]))


class FindingsAuditTests(unittest.TestCase):
    lines = staticmethod(lambda path: {"src/a.py": 40, "src/b.py": 10}.get(path))

    def audit(self, findings, changed=frozenset({"src/a.py"})):
        body = "**结论：** 有 1 条阻塞项\n" + (f"<!-- findings: {json.dumps(findings)} -->" if findings is not None else "")
        return review.audit_findings(body, changed, self.lines)

    def test_a_valid_list_passes(self):
        self.assertEqual([], self.audit([{"id": "B1", "severity": "blocking", "file": "src/a.py", "line": 12}]))
        self.assertEqual([], self.audit([]))

    def test_a_missing_or_malformed_list_is_reported(self):
        self.assertIn("没有附 findings", self.audit(None)[0])
        body = "<!-- findings: [not json] -->"
        self.assertIn("不是有效的 JSON", review.audit_findings(body, set(), self.lines)[0])

    def test_wrong_files_lines_and_severities_are_reported(self):
        problems = self.audit([
            {"id": "B1", "severity": "blocking", "file": "src/gone.py", "line": 1},
            {"id": "S1", "severity": "suggestion", "file": "src/a.py", "line": 41},
            {"id": "B2", "severity": "blocking", "file": "src/b.py", "line": 3},
            {"id": "X1", "severity": "major"},
        ])
        self.assertEqual(4, len(problems))
        self.assertIn("B1", problems[0])
        self.assertIn("41", problems[1])
        self.assertIn("不在本 PR 的改动文件里", problems[2])
        self.assertIn("X1", problems[3])

    def test_a_long_list_of_problems_is_cut_to_a_readable_note(self):
        problems = self.audit([{"id": f"S{i}", "severity": "major"} for i in range(30)])
        self.assertEqual(review.AUDIT_LINES + 1, len(problems))
        self.assertIn("另有 20 条", problems[-1])

    def test_without_the_file_list_only_the_shape_is_checked(self):
        self.assertEqual([], self.audit([{"id": "B1", "severity": "blocking", "file": "src/gone.py"}], changed=None))


class AuthorReplyTests(unittest.TestCase):
    def test_replies_are_owner_comments_after_the_latest_review_fetched_since_it(self):
        github = review.GitHub("owner/project", 25, review.Budget())
        paths = []

        def api(path):
            paths.append(path)
            if "/reviews" in path:
                return [{"user": {"login": review.REVIEW_BOT}, "submitted_at": "2026-10-13T00:00:00Z"},
                        {"user": {"login": review.REVIEW_BOT}, "submitted_at": "2026-10-13T02:00:00Z"},
                        {"user": {"login": "someone"}, "submitted_at": "2026-10-13T05:00:00Z"}]
            return [{"author_association": "OWNER", "created_at": "2026-10-13T01:00:00Z", "body": "old"},
                    {"author_association": "OWNER", "created_at": "2026-10-13T03:00:00Z", "body": "fixed in abc"},
                    {"author_association": "NONE", "created_at": "2026-10-13T04:00:00Z", "body": "drive-by"}]

        github.api = api
        self.assertEqual([{"at": "2026-10-13T03:00:00Z", "body": "fixed in abc"}], github.author_replies())
        self.assertIn("since=2026-10-13T02%3A00%3A00Z", paths[-1])

    def test_exempt_notices_do_not_hide_replies_to_the_last_real_review(self):
        github = review.GitHub("owner/project", 25, review.Budget())
        paths = []

        def api(path):
            paths.append(path)
            if "/reviews" in path:
                return [{"user": {"login": review.REVIEW_BOT}, "submitted_at": "2026-10-13T02:00:00Z",
                         "body": "**结论：** 有 1 条阻塞项"},
                        {"user": {"login": review.REVIEW_BOT}, "submitted_at": "2026-10-13T04:00:00Z",
                         "body": "**结论：** 本次只合并了 main。\n\n" + review.EXEMPT_MARKER}]
            return [{"author_association": "OWNER", "created_at": "2026-10-13T03:00:00Z", "body": "declined S1"},
                    {"author_association": "OWNER", "created_at": "2026-10-13T05:00:00Z", "body": "fixed B1"}]

        github.api = api
        self.assertEqual(["declined S1", "fixed B1"], [reply["body"] for reply in github.author_replies()])
        self.assertIn("since=2026-10-13T02%3A00%3A00Z", paths[-1])

    def test_no_earlier_review_means_no_replies(self):
        github = review.GitHub("owner/project", 25, review.Budget())
        github.api = lambda path: []
        self.assertEqual([], github.author_replies())


class CiTriggerTests(unittest.TestCase):
    def test_editing_a_pull_request_description_does_not_restart_verification(self):
        workflow = (Path(__file__).resolve().parents[1] / "workflows" / "ci.yml").read_text(encoding="utf-8")
        types = next(line for line in workflow.splitlines() if line.strip().startswith("types:"))
        self.assertNotIn("edited", types)
        self.assertIn("synchronize", types)


if __name__ == "__main__":
    unittest.main()
