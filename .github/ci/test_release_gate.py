"""Public release receipts are bound to a trusted issuer, the tested tree and the archive."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

location = Path(__file__).resolve().parents[2] / 'acceptance/clients/release-gate.py'
spec = importlib.util.spec_from_file_location('release_gate', location)
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class ReleaseReceiptTests(unittest.TestCase):
    def test_only_the_latest_status_from_the_designated_issuer_counts(self):
        items = [
            {'context': gate.CONTEXT, 'creator': {'login': 'elsewhere'}, 'state': 'success'},
            {'context': gate.CONTEXT, 'creator': None, 'state': 'success'},
            {'context': 'other', 'creator': {'login': 'issuer'}, 'state': 'success'},
            {'context': gate.CONTEXT, 'creator': {'login': 'Issuer'}, 'state': 'error'},
            {'context': gate.CONTEXT, 'creator': {'login': 'issuer'}, 'state': 'success'},
        ]
        with patch.object(gate, 'api', return_value=items) as api:
            self.assertEqual('error', gate.status('owner/consumer', 'head', 'issuer')['state'])
            api.assert_called_with('owner/consumer', 'commits/head/statuses?per_page=100')

    def test_a_squash_reuses_only_the_verified_tree_of_its_merged_first_party_pr(self):
        repo = 'owner/consumer'
        def pull(head, owner=repo, merge='squash'):
            return {'head': {'repo': {'full_name': owner}, 'sha': head}, 'base': {'ref': 'main'},
                    'merged_at': '2026-01-01T00:00:00Z', 'merge_commit_sha': merge}
        data = {'git/commits/squash': {'tree': {'sha': 'tree'}},
                'commits/squash/pulls': [pull('wrong', merge='other'), pull('fork', owner='fork/consumer'),
                                         pull('changed'), pull('same')],
                'git/commits/changed': {'tree': {'sha': 'different'}},
                'git/commits/same': {'tree': {'sha': 'tree'}}}
        with patch.object(gate, 'api', side_effect=lambda _, route: data[route]):
            self.assertEqual(['same'], list(gate.merged_heads(repo, 'squash')))
