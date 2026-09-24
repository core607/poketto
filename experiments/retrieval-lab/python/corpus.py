"""Pinned scrydb adapter. Corpus, relevance labels and run state have separate owners."""
import hashlib
import json
import math
import sqlite3
from pathlib import Path

import numpy as np
import sqlite_vec
from scrydb import Index


def digest(path):
    with open(path, "rb") as stream:
        checksum = hashlib.sha256()
        while block := stream.read(1024 * 1024):
            checksum.update(block)
        return checksum.hexdigest()


class Corpus(Index):
    def __init__(self, path):
        self.db_path = Path(path).resolve(strict=True)
        self.conn = sqlite3.connect(self.db_path.as_uri() + "?mode=ro", uri=True)
        self.conn.enable_load_extension(True)
        sqlite_vec.load(self.conn)
        self.conn.enable_load_extension(False)
        self.conn.execute("PRAGMA query_only=ON")
        self.conn.execute("PRAGMA cache_size=-32768")
        self._model = None

    def retrieve(self, *, query_id=None, query=None, vector=None, mode="hybrid", depth=100):
        if mode not in ("hybrid", "semantic", "lexical") or not 1 <= depth <= 1000:
            raise ValueError("Invalid retrieval mode or depth")
        if query_id is not None:
            rows = self.batch_search([query_id], mode=mode, precision="float", top_k=depth,
                                     candidate_limit=depth, rrf_k=60)[query_id]
        else:
            if vector is not None:
                arr = np.asarray(vector, dtype=np.float32)
                if arr.shape != (4096,) or not np.isfinite(arr).all() or np.linalg.norm(arr) == 0:
                    raise ValueError("Expected a finite, nonzero 4096-dimensional query vector")
                class Preencoded:
                    def encode_queries(self, _):
                        return [arr]
                self._model = Preencoded()
            try:
                rows = self.search(query, mode=mode, precision="float", top_k=depth,
                                   candidate_limit=depth, rrf_k=60)
            finally:
                self._model = None
        return [{"id": r.id, "text": r.document["text"],
                 "score": r.get("rrf_score", r.get("cosine_similarity", r.get("score")))} for r in rows]


def read_qrels(path):
    labels = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        qid, _, docid, relevance = line.split()
        if docid in labels.setdefault(qid, {}):
            raise ValueError("Duplicate relevance judgment")
        labels[qid][docid] = int(relevance)
    return labels


def score(ids, labels, k=10):
    if len(ids) != len(set(ids)):
        raise ValueError("Duplicate ranked document ID")
    # trec_eval ndcg_cut uses linear relevance gains, not 2**relevance - 1.
    dcg = sum(labels.get(doc, 0) / math.log2(i + 2) for i, doc in enumerate(ids[:k]))
    ideal = sum(rel / math.log2(i + 2) for i, rel in enumerate(sorted(labels.values(), reverse=True)[:k]))
    relevant = {doc for doc, rel in labels.items() if rel > 0}
    return {"ndcg10": dcg / ideal if ideal else 0,
            "recall10": len(set(ids[:k]) & relevant) / len(relevant) if relevant else 0}


def fixed_sample(ids, count, seed="retrieval-lab-v1"):
    return sorted(ids, key=lambda q: hashlib.sha256(f"{seed}:{q}".encode()).hexdigest())[:count]


def inspect(corpus, pins, qrels, omitted_empty_ids=()):
    integrity = corpus.conn.execute("PRAGMA integrity_check").fetchall()
    if integrity != [("ok",)]:
        raise ValueError("SQLite integrity check failed")
    counts = {}
    for table in ("documents", "queries"):
        counts[table] = len(getattr(corpus, table))
        for precision in ("float", "int8", "binary"):
            name = f"vec_{table}_{precision}"
            count = corpus.conn.execute(f"SELECT count(*) FROM {name}").fetchone()[0]
            joined = corpus.conn.execute(f"SELECT count(*) FROM {name} v JOIN {table} d ON d.rowid=v.rowid").fetchone()[0]
            if count != counts[table] or joined != count:
                raise ValueError(f"Incomplete {name} identifier mapping")
        sizes = corpus.conn.execute(f"SELECT DISTINCT length(embedding) FROM vec_{table}_float").fetchall()
        if sizes != [(pins["dimensions"] * 4,)]:
            raise ValueError(f"Wrong {table} embedding dimensions")
    docs, queries = set(corpus.documents), set(corpus.queries)
    absent_queries = sorted(set(qrels) - queries)
    absent_docs = sorted({d for labels in qrels.values() for d in labels} - docs)
    if absent_queries or set(absent_docs) - set(omitted_empty_ids):
        raise ValueError(f"Uncovered test labels: queries={absent_queries}, documents={absent_docs}")
    if len(qrels) != pins["expectedTestQueries"]:
        raise ValueError("Unexpected number of test questions")
    return {**counts, "testQueries": len(qrels), "testJudgments": sum(map(len, qrels.values())),
            "dimensions": pins["dimensions"], "integrity": "ok",
            "testLabelCoverage": "complete" if not absent_docs else "missing verified empty documents; original labels retained",
            "missingJudgedEmptyIds": absent_docs,
            "affectedQueries": [q for q, labels in qrels.items() if set(labels) & set(absent_docs)]}
