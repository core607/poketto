"""One-request-per-line private pipe; evaluation labels never enter model requests."""
import json
import sys
from pathlib import Path
from corpus import Corpus, read_qrels, score


def serve(root):
    with Corpus(root / "fiqa.db") as corpus:
        manifest = json.loads((root / "corpus-manifest.json").read_text(encoding="utf-8"))
        labels = {**read_qrels(root / "dev.trec"), **read_qrels(root / "test.trec")}
        for line in sys.stdin:
            request = json.loads(line)
            try:
                op = request["op"]
                if op == "retrieve":
                    result = corpus.retrieve(**request["args"])
                elif op == "query":
                    result = corpus.queries[request["qid"]]["text"]
                elif op == "vector":
                    result = corpus.query_embeddings[request["qid"]].tolist()
                elif op == "documents":
                    result = [{"id": doc, "text": corpus.documents[doc]["text"],
                               "path": manifest["documentPaths"][doc]} for doc in request["ids"]]
                elif op == "score":
                    result = score(request["ids"], labels[request["qid"]])
                else:
                    raise ValueError("Unknown data operation")
                response = {"id": request["id"], "result": result}
            except Exception as error:
                response = {"id": request["id"], "error": type(error).__name__ + ": " + str(error)}
            print(json.dumps(response, ensure_ascii=False, allow_nan=False), flush=True)


if __name__ == "__main__":
    serve(Path(sys.argv[1]))
