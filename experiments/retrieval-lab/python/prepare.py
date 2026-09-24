"""Download, audit, organize and calibrate FiQA without making model API calls."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time
import zipfile

import numpy as np
from sklearn.cluster import MiniBatchKMeans
from sklearn.feature_extraction.text import TfidfVectorizer
from threadpoolctl import threadpool_limits

from corpus import Corpus, digest, fixed_sample, inspect, read_qrels, score

HERE = Path(__file__).resolve().parent.parent
PINS = json.loads((HERE / "dataset.json").read_text())
BASE = f'https://huggingface.co/datasets/{PINS["dataset"]}/resolve/{PINS["revision"]}'


def download(url, path, expected=None):
    if not path.exists():
        part = path.with_suffix(path.suffix + ".part")
        subprocess.run(["curl", "--fail", "--location", "--retry", "3", "--connect-timeout", "15",
                        "--max-time", "1800", "--output", str(part), url], check=True)
        part.replace(path)
    actual = digest(path)
    if expected and actual != expected:
        raise ValueError(f"Checksum mismatch: {path.name}")
    return actual


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def fetch(root):
    root.mkdir(parents=True, exist_ok=True)
    hashes = {"fiqa.db": download(f'{BASE}/{PINS["databasePath"]}', root / "fiqa.db", PINS["databaseSha256"])}
    for split in ("dev", "test"):
        hashes[f"{split}.trec"] = download(f"{BASE}/qrels/fiqa/{split}.trec", root / f"{split}.trec")
    hashes["author-float.trec"] = download(f"{BASE}/runs/beir/fiqa-cosine_float.txt", root / "author-float.trec")
    hashes["beir-fiqa.zip"] = download("https://public.ukp.informatik.tu-darmstadt.de/thakur/BEIR/datasets/fiqa.zip", root / "beir-fiqa.zip")
    write_json(root / "downloads.json", {"pins": PINS, "sha256": hashes})


def audit(root):
    if digest(root / "fiqa.db") != PINS["databaseSha256"]:
        raise ValueError("Database hash mismatch")
    with Corpus(root / "fiqa.db") as corpus:
        original = {}
        with zipfile.ZipFile(root / "beir-fiqa.zip") as archive:
            with archive.open("fiqa/corpus.jsonl") as source:
                for line in source:
                    doc = json.loads(line)
                    original[doc["_id"]] = (doc.get("title", "") + "\n\n" + doc["text"]).strip()
        ids = set(corpus.documents)
        missing = sorted(set(original) - ids)
        extra = sorted(ids - set(original))
        changed = [doc for doc in ids & set(original) if corpus.documents[doc]["text"].strip() != original[doc]]
        omitted_empty = [doc for doc in missing if original[doc] == ""]
        if missing != omitted_empty:
            raise ValueError("Precomputed corpus omits nonempty original documents")
        result = inspect(corpus, PINS, read_qrels(root / "test.trec"), omitted_empty)
        result["standardBeir"] = {"documents": len(original), "missingIds": missing, "extraIds": extra,
                                  "changedTextIds": sorted(changed), "missingDocuments": {k: original[k] for k in missing}}
        # Missing unlabeled source documents are disclosed, never silently restored without vectors.
        result["corpusVariant"] = "scrydb-eval FiQA; see standardBeir differences"
        result["pins"] = PINS
        result["databaseSha256"] = digest(root / "fiqa.db")
        if extra or changed:
            write_json(root / "audit.json", result)
            raise ValueError("Unexpected corpus text changes; inspect audit.json before proceeding")
    write_json(root / "audit.json", result)
    return result


def organize(root, clusters=32, page_size=100):
    started = time.monotonic()
    target = root / "corpus-source"
    if target.exists():
        raise ValueError("corpus-source already exists; use a new data directory for changed preparation")
    with Corpus(root / "fiqa.db") as corpus:
        ids = sorted(corpus.documents)
        texts = [corpus.documents[doc]["text"] for doc in ids]
    if any(not re.fullmatch(r"[A-Za-z0-9_-]+", doc) for doc in ids):
        raise ValueError("Corpus contains an unsafe document identifier")
    # This transform sees documents only. No queries, embeddings, labels or baseline runs are inputs.
    vectorizer = TfidfVectorizer(stop_words="english", max_features=30000, min_df=2,
                                 max_df=0.8, dtype=np.float32, sublinear_tf=True)
    matrix = vectorizer.fit_transform(texts)
    terms = vectorizer.get_feature_names_out()
    with threadpool_limits(limits=1):
        model = MiniBatchKMeans(n_clusters=clusters, random_state=42, n_init=10, batch_size=1024)
        assignments = model.fit_predict(matrix)
    target.mkdir()
    navigation = ["# FiQA corpus", "", "Each .md document preserves its original text. Its filename is its document ID.",
                  "README.md files are derived navigation, not evidence. Topics overlap; search across folders as needed.",
                  "No relevance labels or evaluation questions are included.", ""]
    manifest = {}
    def keywords(indices, n=8):
        weights = np.asarray(matrix[indices].mean(axis=0)).ravel()
        return [str(terms[i]) for i in weights.argsort()[-n:][::-1] if weights[i] > 0]
    for group in range(clusters):
        indices = np.flatnonzero(assignments == group).tolist()
        if not indices:
            continue
        words = keywords(indices)
        name = f"topic-{group:02d}-" + "-".join(words[:3])
        folder = target / "corpus" / name
        folder.mkdir(parents=True)
        navigation.append(f"- [ {' / '.join(words)} ](corpus/{name}/README.md): {len(indices)} documents")
        pages = [f"# {' / '.join(words)}", "", "Pages are ordered by original ID; previews are extractive.", ""]
        for offset in range(0, len(indices), page_size):
            group_ids = indices[offset:offset + page_size]
            page = f"page-{offset // page_size:03d}"
            leaf = folder / page
            leaf.mkdir()
            pages.append(f"- [{page}]({page}/README.md): {', '.join(keywords(group_ids))}")
            entries = [f"# {page}", "", "Document IDs and original-text previews:", ""]
            for i in group_ids:
                doc, text = ids[i], texts[i]
                path = leaf / f"{doc}.md"
                path.write_bytes(text.encode("utf-8"))
                manifest[doc] = path.relative_to(target).as_posix()
                preview = " ".join(text.split())[:160].replace("[", "\\[").replace("]", "\\]")
                entries.append(f"- [{doc}]({doc}.md): {preview}")
            (leaf / "README.md").write_text("\n".join(entries) + "\n", encoding="utf-8")
        (folder / "README.md").write_text("\n".join(pages) + "\n", encoding="utf-8")
    (target / "README.md").write_text("\n".join(navigation) + "\n", encoding="utf-8")
    env = {**os.environ, "GIT_AUTHOR_DATE": "2026-09-24T00:00:00Z", "GIT_COMMITTER_DATE": "2026-09-24T00:00:00Z"}
    def git(*args):
        return subprocess.check_output(["git", "-c", "gc.auto=0", "-c", "maintenance.auto=false", "-C", str(target), *args], env=env, text=True).strip()
    git("init", "-q", "-b", "main")
    git("-c", "core.autocrlf=false", "add", ".")
    git("-c", "user.name=Retrieval lab", "-c", "user.email=lab@example.invalid", "commit", "-qm", "Prepare corpus navigation")
    commit = git("rev-parse", "HEAD")
    git("bundle", "create", str((root / "corpus.bundle").resolve()), "HEAD")
    files = [p for p in target.rglob("*") if p.is_file() and ".git" not in p.relative_to(target).parts]
    report = {"commit": commit, "bundleSha256": digest(root / "corpus.bundle"),
              "bundleBytes": (root / "corpus.bundle").stat().st_size, "documents": len(ids), "files": len(files),
              "textBytes": sum(p.stat().st_size for p in files), "preparationSeconds": time.monotonic() - started,
              "apiCost": 0, "method": "TF-IDF / MiniBatchKMeans / extractive navigation", "seed": 42,
              "clusters": clusters, "pageSize": page_size, "documentPaths": manifest}
    write_json(root / "corpus-manifest.json", report)
    return {k: v for k, v in report.items() if k != "documentPaths"}


def calibrate(root):
    labels = read_qrels(root / "test.trec")
    author = {qid: [] for qid in labels}
    with (root / "author-float.trec").open() as source:
        for line in source:
            qid, _, doc, rank, value, _ = line.split()
            if qid in author and int(rank) <= 10:
                author[qid].append((int(rank), doc))
    results = []
    with Corpus(root / "fiqa.db") as corpus:
        for qid in sorted(labels):
            start = time.monotonic()
            ids = [r["id"] for r in corpus.retrieve(query_id=qid, mode="semantic", depth=10)]
            expected = [doc for _, doc in sorted(author[qid])]
            if len(expected) != 10:
                raise ValueError(f"Author run does not cover {qid}")
            results.append({"qid": qid, "ids": ids, **score(ids, labels[qid]),
                            "author": score(expected, labels[qid]), "sameTop10": ids == expected,
                            "seconds": time.monotonic() - start})
    report = {"questions": len(results), "ndcg10": sum(r["ndcg10"] for r in results) / len(results),
              "authorNdcg10": sum(r["author"]["ndcg10"] for r in results) / len(results),
              "recall10": sum(r["recall10"] for r in results) / len(results),
              "sameTop10": sum(r["sameTop10"] for r in results), "rows": results,
              "timing": "offline precomputed query vectors; query embedding excluded"}
    report["passed"] = abs(report["ndcg10"] - report["authorNdcg10"]) < 1e-6 and report["questions"] == PINS["expectedTestQueries"]
    write_json(root / "calibration.json", report)
    write_json(root / "splits.json", {"seed": "retrieval-lab-v1", "dev": fixed_sample(read_qrels(root / "dev.trec"), 20),
                                     "test": fixed_sample(labels, 100), "humanReview": fixed_sample(labels, 20)})
    if not report["passed"]:
        raise ValueError("Dense baseline differs from pinned author run")
    return {k: v for k, v in report.items() if k != "rows"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["fetch", "audit", "organize", "calibrate"])
    parser.add_argument("--data", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(globals()[args.action](args.data.resolve()), ensure_ascii=False, indent=2))
