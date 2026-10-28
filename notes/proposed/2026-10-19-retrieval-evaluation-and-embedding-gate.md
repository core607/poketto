# Retrieval Evaluation and Embedding Gate

Date: 2026-10-19

## Problem

[Ranked keyword search](../implemented/2026-10-19-ranked-plaza-keyword-search.md)
leaves an index or embeddings waiting for evidence, and nothing yet produces that
evidence. The [retrieval laboratory](../implemented/2026-09-24-retrieval-lab.md)
compares agentic and embedding retrieval on FiQA, an English financial corpus whose
formal batches have not run; it says little about Chinese creative writing. Creator
QA keeps no question text, so production cannot show which questions failed.

## Proposal

1. **Evaluation set.** Write 20 to 30 questions against current public Poketto
   articles, each with the articles and quotes that answer it. Include questions
   phrased in words the answer does not use, such as a theme the article never names,
   and questions with no answer. Store the set beside the fixed
   [QA corpus](../../acceptance/qa/README.md), pinned to the commits it was written
   against.
2. **Replay.** Run the set through the QA loop with the default model, as a suite of
   the [evaluation entrance](2026-10-28-agent-evaluation-entrance.md), and record, per
   question, whether every expected article was read, the tool calls spent, and
   whether citations passed revalidation. Keep failed and capped questions.
3. **Aggregate counters.** Count, per QA question, search calls, zero-result searches
   and page turns, without question or query text, so production shows whether
   agents still page through search.
4. **Embedding gate.** Add embeddings only if the replay shows expected articles that
   no keyword the model tried could reach. If added, compute vectors for public
   articles only, keep them with the snapshot keyed by blob, cache them on disk so a
   restart does not recompute them, drop them with withdrawal, and fuse their
   ranking with the keyword ranking. A reranking model stays out unless a further
   replay shows ordering errors the agent does not correct by reading.

## Acceptance criteria

- The evaluation set and its replay report are committed, and the report states the
  model, commit and every failure.
- The counters appear in the existing request diagnostics without query text.
- The change that resolves the gate either implements embeddings with the replay
  showing the improvement, or moves this proposal to rejected/ with the replay as
  the reason.

## Alternatives

- **Finish the FiQA batches first.** They measure a different language and domain,
  and their cost buys no answer about Poketto content.
- **Log production questions.** It would give real failures but break the creator QA
  promise that question text is not retained.

## Consequences

Writing questions takes an author's time, and replays spend model budget. The set
tracks content that can change, so it must be refreshed when its articles move or
are withdrawn.
