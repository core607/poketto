# Evaluation evidence

This directory retains the Sandcrab evaluation receipts recorded while its
implementation shared the Poketto repository. The independent
[Sandcrab distribution](https://github.com/core607/sandcrab/blob/main/evals/README.md)
owns the Inspect harness, programming corpus, published SDK consumers, model
configuration and evaluation workflows. Poketto's [native release acceptance](../executor-native/README.md#release-acceptance)
verifies its application adapters against the pinned executable.

The retained receipts distinguish scripted protocol checks from model-directed
programming attempts. They identify their source and corpus revisions, clients,
models, outcomes, usage and known costs. Fixed-corpus results measure those cases;
they are not general programming or production-capacity estimates.

- [Native programming client](evidence/2026-10-29-workspace-model.json)
- [Initial SDK batch](evidence/2026-10-30-workspace-sdk-model.json)
- [SDK tool-error diagnosis](evidence/2026-10-31-workspace-sdk-model.json)
- [SDK batch after the harness correction](evidence/2026-10-31-workspace-sdk-model-post-fix.json)
- [Hardening evaluation summary](evidence/2026-11-04-sandcrab-hardening/summary.json)

Original failures and later results retain their separate identities.
New verification commits only aggregate summaries; raw transcripts, submissions
and cleanup records remain in CI artifacts or protected operator storage.
[Product agent and QA evaluations](../notes/proposed/2026-10-28-agent-evaluation-entrance.md)
remain proposed consumer work.
