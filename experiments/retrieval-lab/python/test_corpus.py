import tempfile
import unittest
from pathlib import Path
from scrydb import Index
from corpus import Corpus, fixed_sample, inspect, score


class CorpusTests(unittest.TestCase):
    def test_rowid_mapping_readonly_retrieval_and_missing_judgments(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "tiny.db"
            with Index.open(path) as index:
                index.index_documents([{"id": "z", "text": "Bond interest", "emb": [1., 0., 0., 0., 0., 0., 0., 0.]},
                                       {"id": "a", "text": "Equity prices", "emb": [0., 1., 0., 0., 0., 0., 0., 0.]}], store_int8_embeddings=True)
                index.index_queries([{"id": "q", "text": "Bond yield", "emb": [1., 0., 0., 0., 0., 0., 0., 0.]}], store_int8_embeddings=True)
            with Corpus(path) as corpus:
                rows = corpus.retrieve(query_id="q", mode="semantic", depth=2)
                self.assertEqual([r["id"] for r in rows], ["z", "a"])
                self.assertEqual(rows[0]["text"], "Bond interest")
                with self.assertRaises(Exception):
                    corpus.conn.execute("DELETE FROM documents")
                with self.assertRaisesRegex(ValueError, "Uncovered test labels"):
                    inspect(corpus, {"dimensions": 8, "expectedTestQueries": 1}, {"q": {"absent": 1}})
                report = inspect(corpus, {"dimensions": 8, "expectedTestQueries": 1}, {"q": {"empty": 1}}, ["empty"])
                self.assertEqual(report["missingJudgedEmptyIds"], ["empty"])

    def test_metrics_keep_missing_relevant_documents_in_denominator(self):
        metrics = score(["a", "irrelevant"], {"a": 1, "empty": 1})
        self.assertEqual(metrics["recall10"], .5)
        self.assertAlmostEqual(metrics["ndcg10"], 1 / (1 + 1 / 1.584962500721156))
        with self.assertRaises(ValueError):
            score(["a", "a"], {"a": 1})

    def test_fixed_sample_does_not_depend_on_input_order(self):
        ids = [str(i) for i in range(100)]
        self.assertEqual(fixed_sample(ids, 20), fixed_sample(list(reversed(ids)), 20))
        self.assertEqual(len(set(fixed_sample(ids, 20))), 20)


if __name__ == "__main__":
    unittest.main()
