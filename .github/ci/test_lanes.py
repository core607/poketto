import unittest

from lanes import FULL, select


class LaneSelectionTests(unittest.TestCase):
    def test_push_unknown_and_empty_changes_keep_all_checks(self):
        for event, paths in [('push', ['README.md']), ('pull_request', []),
                             ('pull_request', ['executor-service/worker.py', 'src/main/a.java'])]:
            with self.subTest(event=event, paths=paths):
                self.assertEqual(FULL, select(event, paths))

    def test_release_pins_fixtures_and_deployment_checks_follow_their_consumers(self):
        pin = 'executor-service/sandcrab-release.properties'
        self.assertEqual({'docs', 'image', 'load', 'stack'}, select('pull_request', [pin]))
        self.assertEqual({'docs', 'load'}, select('pull_request', ['executor-native/java_native_fixture.py']))
        self.assertEqual({'docs', 'load', 'stack'}, select('pull_request', ['deploy/native_repository.py']))
        for name in ('native-host.py', 'native-load.py', 'native_repository_fixture.py', 'release-gate.py',
                     'account-http.py', 'games-http.py', 'mcp_http.py'):
            self.assertEqual({'docs', 'load'}, select('pull_request', ['acceptance/clients/' + name]))
        self.assertEqual({'docs', 'load'}, select('pull_request', ['.github/ci/sandcrab-release-check.sh']))

    def test_retained_evidence_does_not_schedule_a_load(self):
        summary = 'executor-native/evidence/sandcrab-l5/summary.json'
        self.assertEqual({'docs'}, select('pull_request', [summary]))
        self.assertEqual({'docs'}, select('pull_request', [summary, 'evals/evidence/model.json']))
        self.assertEqual(FULL, select('pull_request', ['evals/new_harness.py']))

    def test_retrieval_consumer_and_its_workflow_select_the_laboratory(self):
        for path in ('experiments/retrieval-lab/src/worker.ts', '.github/workflows/retrieval-corpus.yml',
                     '.github/ci/sandcrab-corpus-check.sh'):
            self.assertEqual({'docs', 'retrieval-lab'}, select('pull_request', [path]))

    def test_move_out_of_unknown_surface_still_requires_its_original_checks(self):
        self.assertEqual(FULL, select('pull_request', ['frontend/app.ts', 'executor-service/rust/sandcrab/new.rs']))
