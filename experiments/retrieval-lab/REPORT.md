# FiQA calibration

Date: 2026-09-24

## Verified offline result

The pinned [scrydb-eval dataset](https://huggingface.co/datasets/breuert/scrydb-eval/tree/ea5a009b6b0af4f2d164e95abca057090210db77) reproduces its author's float32 dense ranking with the adapter in this experiment. [dataset.json](dataset.json) owns exact revisions, the database hash and query instruction. Run the preparation commands in [README](README.md) to regenerate row-level results and the author comparison in the private data directory.

| Measurement                            |             Result |
| -------------------------------------- | -----------------: |
| Original nonempty documents            |             57,600 |
| Stored queries across splits           |              6,648 |
| Test queries                           |                648 |
| Test relevance judgments               |              1,706 |
| Query/document vector dimensions       |              4,096 |
| nDCG@10, reproduced and author         | 0.6453355936358998 |
| Recall@10                              | 0.7320004284124655 |
| Queries with identical ordered top ten |          648 / 648 |

The database passes SQLite integrity checking and complete row-ID mappings for float32, int8 and binary vectors. Comparison with the standard 57,638-document BEIR corpus finds 38 omitted empty documents and no changed nonempty text. Empty document `117276` is one of six relevant judgments for query `5206`. All original questions and judgments remain in scoring; the missing empty document is not removed from the denominator. The corpus variant is disclosed rather than called an exact copy of every BEIR record.

## Interpretation

This verifies data integration and baseline reproduction. It does not compare neural-reranked RAG against agentic retrieval. It also does not prove zero training contamination, answer correctness, multi-domain behavior or production scalability. The retrieved vectors were prepared by the dataset author; this run did not pay their original embedding bill. Timings exclude online query embedding.

## Remaining experiment

The isolated Linux worker acceptance passes on all 57,600 documents and 630 navigation files, including denied host/network access, captured output, fresh-copy restoration, timeout recovery and cleanup after killing the client process. The repository's existing Java native `ephemeral-lifecycle` probe reports successful combined acceptance and cleanup. Browser acceptance exercises the page with clearly marked synthetic model replies; it does not supply retrieval-quality evidence.

One Windows corpus preparation produced 54,962,751 text bytes and a 30,493,785-byte Git bundle. The observed 719.75 seconds includes initial Git preparation and automatic maintenance overhead. It is not a Linux serving-throughput measurement. Directory construction made no model calls; peak preparation memory was not measured in that run.

Complete real-provider query-vector compatibility, the twenty development pairs, the fixed 100-test-query comparison, and human citation-support review before reporting a preferred retrieval route. Keep failed, interrupted and capped questions in the analysis. Record model identities, separate provider currencies, unknown usage and preparation costs. Private questions and integration with production QA remain later decisions.
