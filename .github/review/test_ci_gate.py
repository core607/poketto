import copy
import contextlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
import zipfile
from unittest.mock import patch

import review
from test_review import FakeGitHub, FakeProvider


class CiGateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.output = Path(self.temp.name)
        self.revision = {"base": "a" * 40, "head": "b" * 40, "base_ref": "main"}
        self.github = FakeGitHub(self.revision)
        self.provider = FakeProvider()
        self.run = {"id": 7, "path": ".github/workflows/ci.yml", "event": "pull_request",
                    "status": "completed", "conclusion": "success", "head_sha": self.revision["head"],
                    "repository": {"full_name": self.github.repository},
                    "pull_requests": [{"number": 25, "base": {"sha": self.revision["base"], "ref": "main"},
                                       "head": {"sha": self.revision["head"]}}]}
        self.jobs = [{"name": "verify", "conclusion": "success"}]
        self.calls = []
        def api(path):
            self.calls.append(path)
            if "/jobs?" in path:
                return {"jobs": self.jobs}
            if "workflows/ci.yml" in path:
                return {"workflow_runs": [self.run]}
            return self.run
        self.github.api = api

    def run_main(self, dispatch=False, restore_error=None):
        event = {"inputs": {"pr_number": "25"}} if dispatch else {"workflow_run": self.run}
        event_file = self.output / "event.json"
        event_file.write_text(json.dumps(event))
        env = {"REVIEW_OUTPUT": str(self.output), "GITHUB_EVENT_PATH": str(event_file),
               "GITHUB_EVENT_NAME": "workflow_dispatch" if dispatch else "workflow_run",
               "GITHUB_REF": "refs/heads/main", "GITHUB_REPOSITORY": self.github.repository,
               "GITHUB_RUN_ID": "9", "GITHUB_OUTPUT": str(self.output / "step-output"),
               "GITHUB_STEP_SUMMARY": str(self.output / "summary")}
        data = b"diff --git a/app.py b/app.py\n@@ -0,0 +1 @@\n+value = 1\n"
        with patch.dict(os.environ, env), patch.object(review, "GitHub", return_value=self.github), \
                patch.object(review, "Provider", return_value=self.provider) as factory, \
                patch.object(review, "fetch_diff", return_value=(self.revision["base"], data)), \
                patch.object(review.review_session, "restore", return_value=None, side_effect=restore_error):
            status = review.main()
        return status, factory

    def test_successful_exact_verify_precedes_provider_and_posts_current_head(self):
        status, factory = self.run_main()
        self.assertEqual(0, status)
        factory.assert_called_once()
        self.assertTrue(any("/jobs?" in path for path in self.calls))
        self.assertEqual(self.revision["head"], self.github.posts[0]["commit_id"])
        self.assertIn("pr_number=25", (self.output / "step-output").read_text())

    def test_failed_upstream_does_not_construct_provider(self):
        self.run["conclusion"] = "failure"
        status, factory = self.run_main()
        self.assertEqual(0, status)
        factory.assert_not_called()

    def test_manual_dispatch_also_requires_successful_verify(self):
        self.run["status"], self.run["conclusion"] = "in_progress", None
        status, factory = self.run_main(dispatch=True)
        self.assertEqual(1, status)
        factory.assert_not_called()

    def test_pending_rerun_does_not_fall_back_to_an_older_success(self):
        older = copy.deepcopy(self.run)
        self.run["status"], self.run["conclusion"] = "in_progress", None
        api = self.github.api
        self.github.api = lambda path: {"workflow_runs": [self.run, older]} if "workflows/" in path else api(path)
        self.assertFalse(review.verified_ci(self.github, self.revision))

    def test_metadata_skips_and_runs_without_verify_do_not_block_dispatch(self):
        older = copy.deepcopy(self.run)
        older["id"] = 6
        for conclusion, jobs in [("skipped", []), ("success", [{"name": "metadata-only", "conclusion": "skipped"}])]:
            with self.subTest(conclusion=conclusion):
                self.run["conclusion"] = conclusion
                def api(path):
                    if "workflows/" in path:
                        return {"workflow_runs": [self.run, older]}
                    return {"jobs": jobs if "/7/jobs?" in path else self.jobs}
                self.github.api = api
                self.assertTrue(review.verified_ci(self.github, self.revision))

    def test_failed_latest_actual_verify_still_blocks_dispatch(self):
        older = copy.deepcopy(self.run)
        self.run["conclusion"] = "failure"
        api = self.github.api
        self.github.api = lambda path: {"workflow_runs": [self.run, older]} if "workflows/" in path else api(path)
        self.assertFalse(review.verified_ci(self.github, self.revision))

    def test_corrupt_session_zip_produces_controlled_incomplete_without_a_provider(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            status, factory = self.run_main(restore_error=zipfile.BadZipFile("fixture bad header"))
        self.assertEqual(1, status)
        factory.assert_not_called()
        manifest = json.loads((self.output / "manifest.json").read_text())
        self.assertEqual("incomplete", manifest["state"])
        self.assertIn("session ZIP is corrupt", manifest["reason"])
        self.assertNotIn("Traceback", output.getvalue())

    def test_draft_pr_does_not_construct_provider_after_ci(self):
        current = self.github.current
        self.github.current = lambda: {**current(), "draft": True}
        status, factory = self.run_main()
        self.assertEqual(0, status)
        factory.assert_not_called()

    def test_unsupported_base_is_exempt_after_automatic_ci(self):
        current = self.github.current
        pr = current()
        pr["base"]["ref"] = "codex/unrelated-stack"
        self.github.current = lambda: pr
        status, factory = self.run_main()
        self.assertEqual(0, status)
        factory.assert_not_called()
        self.assertEqual("exempt", json.loads((self.output / "manifest.json").read_text())["state"])

    def test_wrong_run_head_workflow_repository_or_skipped_verify_is_rejected(self):
        original = copy.deepcopy(self.run)
        for key, value in [("head_sha", "c" * 40), ("path", ".github/workflows/other.yml"),
                           ("repository", {"full_name": "other/repo"})]:
            with self.subTest(key=key):
                self.run = {**original, key: value}
                self.assertFalse(review.verified_ci(self.github, self.revision, 7))
        self.run = original
        self.jobs = [{"name": "verify", "conclusion": "skipped"}]
        self.assertFalse(review.verified_ci(self.github, self.revision, 7))

    def test_live_pr_fields_are_not_treated_as_historical_verification(self):
        # GitHub updates this association when the PR changes, even on old workflow runs.
        self.run["pull_requests"][0]["head"]["sha"] = self.revision["head"]
        self.run["head_sha"] = "c" * 40
        self.assertFalse(review.verified_ci(self.github, self.revision, 7))
        self.run["head_sha"] = self.revision["head"]
        self.run["pull_requests"][0]["base"]["sha"] = "d" * 40
        self.assertTrue(review.verified_ci(self.github, self.revision, 7))


if __name__ == "__main__":
    unittest.main()
