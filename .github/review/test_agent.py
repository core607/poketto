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
        self.marker = self.root / "executed-marker"
        (self.repo / "attack.py").write_text(
            "from pathlib import Path\nPath(" + repr(str(self.marker)) + ").write_text('executed')\n")
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

    def test_real_bare_code_tool_round_trip_replays_and_records_provider_reasoning(self):
        result, opener = self.run_agent([
            self.response(self.tool_message(), "tool_calls"), self.response({"content": "Verified consumer."})])
        self.assertEqual("Verified consumer.", result)
        requests = [json.loads(call.args[0].data) for call in opener.call_args_list]
        self.assertEqual(128000, requests[0]["max_tokens"])
        self.assertEqual(128000, requests[1]["max_tokens"])
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
        full_trace = [json.loads(line) for line in (self.root / "agent-trace.jsonl").read_text().split("\n") if line]
        model = next(item for item in full_trace if item["event"] == "model_response")
        self.assertEqual("PRIVATE_REASONING_FIXTURE", model["message"]["reasoning_content"])
        self.assertEqual("tool_calls", model["finish_reason"])
        self.assertTrue(any(item["event"] == "tool_call" for item in full_trace))
        result_record = next(item for item in full_trace if item["event"] == "tool_result")
        self.assertIn("head implementation", result_record["content"])
        self.assertFalse((self.bare / "attack.py").exists())

    def test_trace_redacts_provider_echoes_and_retains_failed_response_for_diagnosis(self):
        self.provider.key = 'credential-with-"-quotes'
        with patch.object(self.provider.opener, "open", return_value=self.response({
                "content": "partial " + self.provider.key, "reasoning_content": "diagnostic reasoning"}, finish="length")):
            with self.assertRaises(review.Incomplete):
                review.AgentReview(self.provider, self.tools, lambda: None, self.root, "failed", 2).review(self.body)
        trace = [json.loads(line) for line in (self.root / "agent-trace.jsonl").read_text().split("\n") if line]
        response = next(item for item in trace if item["event"] == "model_response")
        self.assertEqual("partial [REDACTED]", response["message"]["content"])
        self.assertEqual("diagnostic reasoning", response["message"]["reasoning_content"])
        self.assertEqual("length", response["finish_reason"])
        self.assertEqual("incomplete", trace[-1]["event"])

    def test_cache_usage_is_measured_and_long_lines_are_marked_truncated(self):
        raw = json.loads(self.response({"content": "complete"}).getvalue())
        raw["usage"].update(prompt_cache_hit_tokens=800, prompt_cache_miss_tokens=200)
        self.run_agent([io.BytesIO(review.encoded(raw))])
        trace = json.loads((self.root / "probe-operations.json").read_bytes())
        self.assertEqual(800, trace[0]["prompt_cache_hit_tokens"])
        self.assertEqual(200, trace[0]["prompt_cache_miss_tokens"])
        for character in ("猫", "\x01", "\\", '"'):
            with patch.object(self.tools, "blob", return_value=character * 30000 + "\nsecond line\n"):
                result = self.call("read", path="consumer.py", limit=1)
            self.assertTrue(result["lines"][0]["truncated"])
            self.assertTrue(result["lines"][0]["text"])
            self.assertEqual(1, result["next_offset"])
            self.assertLessEqual(len(review.encoded(result)), TOOL_BYTES)

    def test_source_line_numbers_follow_git_lf_and_blob_cache_reuses_objects(self):
        text = "one\rtwo\fthree\u2028four\nsecond\n"
        with patch.object(self.tools, "blob", return_value=text):
            result = self.call("read", path="consumer.py", offset=1, limit=1)
            self.assertEqual([{"line": 2, "text": "second"}], result["lines"])
            search = self.call("search", path="consumer.py", query="four")
            self.assertEqual([{"path": "consumer.py", "line": 1}], search["entries"])
        with patch.object(self.tools, "git", wraps=review.git) as git:
            self.call("read", path="consumer.py", limit=1)
            count = git.call_count
            self.call("read", path="consumer.py", offset=1, limit=1)
            self.call("search", path="consumer.py", query="head")
            self.assertEqual(count, git.call_count)

    def test_repeated_arguments_with_new_call_id_warn_then_finalize_without_rereading(self):
        second = self.tool_message("another_id", {"path": "consumer.py", "revision": "head", "action": "read"})
        with patch.object(self.tools, "call", wraps=self.tools.call) as tool:
            with patch.object(self.provider.opener, "open", side_effect=[
                    self.response(self.tool_message(), "tool_calls"),
                    self.response(second, "tool_calls"), self.response({"content": "Final observed findings."})]) as opener:
                result = review.AgentReview(self.provider, self.tools, lambda: None, self.root, "loop", 10).review(self.body)
                self.assertEqual("Final observed findings.", result)
                tool.assert_called_once()
                final = json.loads(opener.call_args.args[0].data)
                self.assertEqual("none", final["tool_choice"])
                self.assertIn("Repeated tool request", final["messages"][-2]["content"])
                self.assertEqual(review.FINAL_INSTRUCTION, final["messages"][-1]["content"])

    def test_repository_script_is_returned_as_text_without_executing_its_side_effect(self):
        result, opener = self.run_agent([
            self.response(self.tool_message(arguments={"action": "read", "revision": "head", "path": "attack.py"}), "tool_calls"),
            self.response({"content": "Inspected script as data."})])
        self.assertEqual("Inspected script as data.", result)
        followup = json.loads(opener.call_args.args[0].data)
        self.assertIn("write_text", followup["messages"][-2]["content"])
        self.assertFalse(self.marker.exists())

    def test_distinct_malformed_arguments_can_be_corrected_without_false_repeat_shutdown(self):
        message = self.tool_message()
        first = message["tool_calls"][0]
        first["function"]["arguments"] = "{broken"
        message["tool_calls"].append({"id": "bad_2", "type": "function", "function": {
            "name": "repository", "arguments": "[broken"}})
        with patch.object(self.provider.opener, "open", side_effect=[
                self.response(message, "tool_calls"), self.response(self.tool_message("fixed"), "tool_calls"),
                self.response({"content": "Inspected valid source."})]) as opener:
            result = review.AgentReview(self.provider, self.tools, lambda: None, self.root, "bad-json", 5).review(self.body)
        self.assertEqual("Inspected valid source.", result)
        second = json.loads(opener.call_args_list[1].args[0].data)
        self.assertNotEqual("none", second.get("tool_choice"))
        self.assertTrue(all("not valid JSON" in item["content"] for item in second["messages"][-2:]))

    def test_deep_argument_json_returns_tool_error_instead_of_crashing(self):
        message = self.tool_message()
        message["tool_calls"][0]["function"]["arguments"] = "[" * 1500 + "]" * 1500
        result, opener = self.run_agent([
            self.response(message, "tool_calls"), self.response({"content": "Unable to verify that request."})])
        self.assertEqual("Unable to verify that request.", result)
        last = json.loads(opener.call_args.args[0].data)
        self.assertIn("not valid JSON", last["messages"][-2]["content"])

    def test_cached_unreadable_blob_does_not_accumulate_exception_tracebacks(self):
        import traceback
        entry = self.tools.tree("head")["binary"]
        depths = []
        for _ in range(10):
            try:
                self.tools.blob(entry)
            except Exception as error:
                depths.append(len(list(traceback.walk_tb(error.__traceback__))))
        self.assertEqual(10, len(depths))
        self.assertLessEqual(max(depths), 2)

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
            cursor, names = "", []
            while cursor is not None:
                page = self.call("list", cursor=cursor)
                names.extend(e["path"] for e in page["entries"])
                following = page["next_cursor"]
                self.assertTrue(following is None or following != cursor)
                cursor = following
        self.assertEqual(sorted(self.tools.tree("head")), names)

    def test_pagination_rejects_cursor_reused_for_another_selection(self):
        first = self.call("search", path="lines", query="needle")
        token = first["next_cursor"]
        self.assertIsInstance(token, str)
        for changes in ({"query": "other"}, {"path": ""}, {"revision": "base"}, {"action": "list"}):
            args = {"action": "search", "revision": "head", "path": "lines", "query": "needle", "cursor": token}
            args.update(changes)
            self.assertIn("does not belong", json.loads(self.tools.call("repository", args))["error"])
        restarted = self.call("search", path="lines", query="needle")
        self.assertEqual(first, restarted)

    def test_each_call_has_128k_output_without_a_pr_total_cap(self):
        for index in range(2):
            with patch.object(self.provider.opener, "open", return_value=self.response(
                    {"content": "finished"}, completion=128000)) as opener:
                result = review.AgentReview(self.provider, self.tools, lambda: None, self.root,
                                            "review-" + str(index), 2).review(self.body)
                self.assertEqual("finished", result)
                self.assertEqual(128000, json.loads(opener.call_args.args[0].data)["max_tokens"])

    def test_new_context_over_input_cap_is_not_sent(self):
        with patch.object(self.provider.opener, "open", return_value=self.response(
                self.tool_message(), "tool_calls", prompt=review.INPUT_TOKENS - 1)) as opener:
            with self.assertRaisesRegex(review.Incomplete, "budget"):
                review.AgentReview(self.provider, self.tools, lambda: None, self.root, "probe", 2).review(self.body)
            self.assertEqual(1, opener.call_count)

    def test_exact_initial_request_cap_leaves_space_for_forced_summary(self):
        request = json.loads(self.body)
        request["messages"][1]["content"] += "x" * (review.REQUEST_BYTES - len(self.body))
        body = review.encoded(request)
        self.assertEqual(review.REQUEST_BYTES, len(body))
        with patch.object(self.provider.opener, "open", return_value=self.response({"content": "Final summary."})):
            result = review.AgentReview(self.provider, self.tools, lambda: None, self.root, "last", 1).review(body)
        self.assertEqual("Final summary.", result)
        trace = json.loads((self.root / "last-operations.json").read_bytes())
        self.assertEqual(review.INPUT_TOKENS, trace[0]["input_token_upper_bound"])

    def test_non_utf8_git_path_does_not_hide_valid_source(self):
        blob = subprocess.check_output(["git", "hash-object", "-w", "--stdin"], cwd=self.repo,
                                       input=b"valid source\n").strip()
        tree = subprocess.check_output(["git", "mktree"], cwd=self.repo,
                                       input=b"100644 blob " + blob + b"\tgood.py\n100644 blob " + blob + b"\tbad-\xff.py\n").strip()
        commit = subprocess.check_output(["git", "commit-tree", tree.decode(), "-m", "odd names"],
                                         cwd=self.repo).decode().strip()
        tools = RepositoryTools(self.repo, {"base": commit, "head": commit}, commit, review.Budget(), review.git)
        result = json.loads(tools.call("repository", {"action": "read", "revision": "head", "path": "good.py"}))
        self.assertEqual("valid source", result["lines"][0]["text"])
        self.assertEqual(1, result["unreadable_utf8_paths"])

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

    def test_drift_is_checked_once_per_round_before_next_model_call(self):
        count = 0

        def changed():
            nonlocal count
            count += 1
            if count == 2:
                raise review.Incomplete("stale fixture")

        with patch.object(self.tools, "call", wraps=self.tools.call) as tool:
            with self.assertRaisesRegex(review.Incomplete, "stale"):
                self.run_agent([self.response(self.tool_message(), "tool_calls")], changed)
            tool.assert_called_once()


if __name__ == "__main__":
    unittest.main()
