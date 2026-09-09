import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import review
from repository_tools import RepositoryTools, TOOL_BYTES


class AgentTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / "source"
        self.repo.mkdir()
        self.git("init", "-b", "main")
        self.git("config", "user.name", "Fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        (self.repo / "consumer.py").write_text("def consumer():\n    return 'base implementation'\n")
        self.git("add", ".")
        self.git("commit", "-m", "base")
        base = self.git("rev-parse", "HEAD").strip()
        (self.repo / "consumer.py").write_text("def consumer():\n    return 'head implementation'\n")
        (self.repo / "attack.py").write_text("raise RuntimeError('must never execute')\n")
        (self.repo / "binary").write_bytes(b"binary\0data")
        (self.repo / "oversized").write_bytes(b"x" * 1_000_001)
        (self.repo / "lines").write_text("".join(f"line {i} needle\n" for i in range(230)))
        self.git("add", ".")
        # Record a symlink as a Git object even on Windows without symlink privileges.
        oid = subprocess.check_output(["git", "hash-object", "-w", "--stdin"], cwd=self.repo,
                                      input=b"/etc/passwd").decode().strip()
        self.git("update-index", "--add", "--cacheinfo", "120000," + oid + ",escape")
        self.git("commit", "-m", "head")
        head = self.git("rev-parse", "HEAD").strip()
        self.revision = {"base": base, "head": head, "base_ref": "main"}
        self.bare = self.root / "objects.git"
        self.git("clone", "--bare", "--no-local", str(self.repo), str(self.bare))
        self.tools = RepositoryTools(self.bare, self.revision, base, review.Budget(), review.git)
        self.provider = review.Provider("https://example.invalid", "fixture-key", review.Budget())
        self.body = review.payload("fixture", "trusted rules", self.revision, "PR", "inspect consumer")

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.repo, stderr=subprocess.DEVNULL).decode()

    def call(self, action, **arguments):
        return json.loads(self.tools.call("repository", {"action": action, "revision": "head", **arguments}))

    @staticmethod
    def response(message, finish="stop", prompt=1000, completion=50):
        return io.BytesIO(review.encoded({"choices": [{"message": {"role": "assistant", **message},
                                                       "finish_reason": finish}],
                                         "usage": {"prompt_tokens": prompt, "completion_tokens": completion}}))

    @staticmethod
    def tool_message(call_id="read_1", arguments=None):
        return {"content": "", "reasoning_content": "PRIVATE_REASONING_FIXTURE",
                "tool_calls": [{"id": call_id, "type": "function", "function": {
                    "name": "repository", "arguments": json.dumps(arguments or {
                        "action": "read", "revision": "head", "path": "consumer.py"})}}]}

    def run_agent(self, responses, unchanged=lambda: None):
        with patch.object(self.provider.opener, "open", side_effect=responses) as opener:
            result = review.AgentReview(self.provider, self.tools, unchanged, self.root, "probe", 2).review(self.body)
        return result, opener

    def test_real_bare_code_tool_round_trip_replays_reasoning_only_in_memory(self):
        result, opener = self.run_agent([
            self.response(self.tool_message(), "tool_calls"), self.response({"content": "Verified consumer."})])
        self.assertEqual("Verified consumer.", result)
        requests = [json.loads(call.args[0].data) for call in opener.call_args_list]
        self.assertEqual(150000, requests[0]["max_tokens"])
        self.assertEqual(149950, requests[1]["max_tokens"])
        self.assertEqual("PRIVATE_REASONING_FIXTURE", requests[1]["messages"][-3]["reasoning_content"])
        self.assertIn("head implementation", requests[1]["messages"][-2]["content"])
        self.assertEqual("read_1", requests[1]["messages"][-2]["tool_call_id"])
        self.assertEqual(requests[0]["messages"], requests[1]["messages"][:2])
        self.assertEqual(requests[0]["tools"], requests[1]["tools"])
        trace = (self.root / "probe-operations.json").read_text()
        self.assertNotIn("PRIVATE_REASONING_FIXTURE", trace)
        self.assertNotIn("fixture-key", trace)
        self.assertNotIn("head implementation", trace)
        self.assertIn("consumer.py", trace)
        self.assertFalse((self.bare / "attack.py").exists())

    def test_cache_usage_is_measured_and_long_lines_are_marked_truncated(self):
        raw = json.loads(self.response({"content": "complete"}).getvalue())
        raw["usage"].update(prompt_cache_hit_tokens=800, prompt_cache_miss_tokens=200)
        self.run_agent([io.BytesIO(review.encoded(raw))])
        trace = json.loads((self.root / "probe-operations.json").read_bytes())
        self.assertEqual(800, trace[0]["prompt_cache_hit_tokens"])
        self.assertEqual(200, trace[0]["prompt_cache_miss_tokens"])
        with patch.object(self.tools, "blob", return_value="猫" * 20000 + "\nsecond line\n"):
            result = self.call("read", path="consumer.py", limit=1)
        self.assertTrue(result["lines"][0]["truncated"])
        self.assertEqual(1, result["next_offset"])
        self.assertLessEqual(len(review.encoded(result)), TOOL_BYTES)

    def test_pinned_reads_search_pagination_and_explicit_unsupported_files(self):
        self.assertIn("head implementation", str(self.call("read", path="consumer.py")))
        base = self.tools.call("repository", {"action": "read", "revision": "base", "path": "consumer.py"})
        self.assertIn("base implementation", base)
        # Moving the source branch cannot change the chosen objects.
        (self.repo / "consumer.py").write_text("changed working tree")
        self.assertIn("head implementation", str(self.call("read", path="consumer.py")))
        first = self.call("read", path="lines")
        self.assertEqual(200, first["next_offset"])
        self.assertEqual(30, len(self.call("read", path="lines", offset=200, limit=100)["lines"]))
        search = self.call("search", query="head implementation")
        self.assertTrue(any(e.get("path") == "consumer.py" and e.get("line") == 2 for e in search["entries"]))
        self.assertTrue(search["unsearched"])
        self.assertTrue(all(set(e) == {"path", "line"} for e in search["entries"]))
        first_search = self.call("search", path="lines", query="needle")
        self.assertEqual(200, len(first_search["entries"]))
        second_search = self.call("search", path="lines", query="needle", cursor=first_search["next_cursor"])
        self.assertEqual(list(range(1, 231)), [e["line"] for e in first_search["entries"] + second_search["entries"]])
        for path in ("../consumer.py", "/etc/passwd", ".git/config", "escape", "binary", "oversized"):
            self.assertIn("error", self.call("read", path=path), path)
        for arguments in (None, [], {"action": "read", "revision": []},
                          {"action": "shell", "revision": "head"}):
            self.assertIn("error", json.loads(self.tools.call("repository", arguments)))
        self.assertLessEqual(len(review.encoded(search)), TOOL_BYTES)

    def test_listing_resumes_without_omitting_paths(self):
        with patch("repository_tools.TOOL_BYTES", 280):
            cursor, names = 0, []
            while cursor is not None:
                page = self.call("list", cursor=cursor)
                names.extend(e["path"] for e in page["entries"])
                following = page["next_cursor"]
                self.assertTrue(following is None or following > cursor)
                cursor = following
        self.assertEqual(sorted(self.tools.tree("head")), names)

    def test_shared_budget_includes_reasoning_and_stops_next_review_before_request(self):
        self.run_agent([self.response({"content": "finished"}, completion=150000)])
        with patch.object(self.provider.opener, "open") as opener:
            with self.assertRaisesRegex(review.Incomplete, "budget"):
                review.AgentReview(self.provider, self.tools, lambda: None, self.root, "second", 2).review(self.body)
            opener.assert_not_called()

    def test_new_context_over_input_cap_is_not_sent(self):
        with patch.object(self.provider.opener, "open", return_value=self.response(
                self.tool_message(), "tool_calls", prompt=review.INPUT_TOKENS - 1)) as opener:
            with self.assertRaisesRegex(review.Incomplete, "budget"):
                review.AgentReview(self.provider, self.tools, lambda: None, self.root, "probe", 2).review(self.body)
            self.assertEqual(1, opener.call_count)

    def test_loop_requests_final_answer_at_bound_and_refuses_ignored_tool_choice(self):
        with patch.object(review, "MAX_TURNS", 2):
            with patch.object(self.provider.opener, "open", side_effect=[
                    self.response(self.tool_message(), "tool_calls"),
                    self.response(self.tool_message("read_2"), "tool_calls")]) as opener:
                with self.assertRaisesRegex(review.Incomplete, "tool call bound"):
                    review.AgentReview(self.provider, self.tools, lambda: None, self.root, "probe", 2).review(self.body)
                last = json.loads(opener.call_args.args[0].data)
                self.assertEqual("none", last["tool_choice"])
                self.assertIn("tools", last)
                self.assertEqual(review.FINAL_INSTRUCTION, last["messages"][-1]["content"])

    def test_missing_usage_and_length_stop_never_become_final_review(self):
        for raw in (review.encoded({"choices": [{"message": {"content": "looks complete"}, "finish_reason": "stop"}]}),
                    self.response({"content": "partial"}, finish="length").getvalue()):
            with patch.object(self.provider.opener, "open", return_value=io.BytesIO(raw)):
                with self.assertRaises(review.Incomplete):
                    self.provider.complete(self.body)

    def test_drift_between_model_call_and_read_prevents_tool_execution(self):
        count = 0

        def changed():
            nonlocal count
            count += 1
            if count == 2:
                raise review.Incomplete("stale fixture")

        with patch.object(self.tools, "call") as tool:
            with self.assertRaisesRegex(review.Incomplete, "stale"):
                self.run_agent([self.response(self.tool_message(), "tool_calls")], changed)
            tool.assert_not_called()


if __name__ == "__main__":
    unittest.main()
