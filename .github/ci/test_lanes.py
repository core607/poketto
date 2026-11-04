import unittest

from lanes import FULL, select


class LaneSelectionTests(unittest.TestCase):
    def test_push_unknown_and_empty_changes_keep_all_checks(self):
        for event, paths in [('push', ['README.md']), ('pull_request', []),
                             ('pull_request', ['executor-service/worker.py', 'src/main/a.java'])]:
            with self.subTest(event=event, paths=paths):
                self.assertEqual(FULL, select(event, paths))

    def test_native_only_and_shared_consumer_changes_take_different_paths(self):
        native = 'executor-service/rust/sandcrab/src/config.rs'
        self.assertEqual({'docs', 'image', 'evals'}, select('pull_request', [native]))
        self.assertTrue({'load', 'stack'} <= select('pull_request', [native, 'executor-service/worker.py']))
        self.assertEqual({'docs', 'load'}, select('pull_request', ['acceptance/clients/native-host.py']))

    def test_evidence_and_collector_changes_do_not_schedule_a_load(self):
        summary = 'executor-native/evidence/sandcrab-l5/summary.json'
        self.assertEqual({'docs'}, select('pull_request', [summary]))
        self.assertEqual({'docs', 'collectors'}, select('pull_request', [summary, 'experiments/sandcrab-density/host.py']))

    def test_retrieval_consumer_and_its_workflow_select_the_laboratory(self):
        for path in ('experiments/retrieval-lab/src/worker.ts', '.github/workflows/retrieval-corpus.yml'):
            self.assertEqual({'docs', 'retrieval-lab'}, select('pull_request', [path]))

    def test_move_out_of_unknown_surface_still_requires_its_original_checks(self):
        self.assertEqual(FULL, select('pull_request', ['frontend/app.ts', 'executor-service/rust/sandcrab/new.rs']))
