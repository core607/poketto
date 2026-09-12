# Shared Checks

Date: 2026-09-12
Status: Implemented

## Problem

The [Java style baseline](2026-09-12-java-style-baseline.md) left a debt register: 37 named methods over 60 code lines, and its own note that the register shrinks when its entries are touched. Length was the visible symptom. The cause underneath it is that the same check was written out wherever it was needed.

Measured on `src/main` before this change:

| Answer | Copies | Where |
|---|---|---|
| "is this tree entry a file" | 15, in 5 spellings, under two different rules | 9 files, twice as a private helper named `regular` |
| "is this path in the managed document area" | 2 | next to the constant already holding the prefix |
| Strict UTF-8 decode | 5 identical blocks | `content`, plus one in `executor` |
| Strict UTF-8 encode | 2 identical blocks | `content` |
| Recursive directory delete | 2, disagreeing | `content/internal` |
| Percent-encoded relative link | 2, different algorithms | `content/internal` |
| Markdown document-order walk | 2 | `content` and `content/internal` |
| Directory isolation guard | 3 byte-identical blocks | `assets/internal`, `content/internal` |
| Deferred download stream | 2 | `web/internal` |
| Search bounds, filters and snippet | 2 | `content` and `web/internal` |

Two of those copies had already drifted. The recursive delete in the repository cache cleared the read-only flag with `Files.setAttribute`, which follows a symlink, so it could clear the flag on a file outside the tree; the export staging copy used `NOFOLLOW_LINKS` and only touched regular files. The two relative-link builders were separate algorithms, one over path segments and one through `java.nio.file.Path`, which is the shape a bug hides in: they agree today and nothing holds them together.

`IsolatedRepositoryExecutor.bridgeOperation` was 172 code lines, a single `if` chain over nine unrelated commands, each with its own vocabulary of failure codes.

## Decision

The repeated answers each get one owner, and the callers keep only what genuinely differs between them.

- `RepositoryBlobs` owns both file-mode questions, because there are two and they were being confused for one. `isFile` accepts the executable bit and is asked of anything an author may have written. `isPlainFile` refuses it and is asked by every reader of the media index, which this application writes itself with the mode forced to `REGULAR_FILE`. Naming both keeps the difference visible; collapsing them would have changed what a media index may be.
- `DocumentPathRules.isManaged` answers whether a path is in the managed area, beside the prefix constant that was already there.
- `StrictText` owns both directions of strict UTF-8. Each caller still translates the coding failure itself, because what undecodable bytes mean differs: invalid content, a closed publishing policy, or a media index not to be trusted at all. One inline copy stays, in `IsolatedRepositoryExecutor`, because the executor is its own application module and cannot import `content.internal`.
- `LocalFileTrees.delete` owns recursive deletion, using the stricter of the two behaviors. Callers keep their own translation of the failure.
- `RelativeLinks.from` owns the relative link, using the path-segment algorithm, which never consults the local filesystem or its separator. A document at the repository root is refused rather than answered from the root: the replaced `java.nio.file.Path` version failed there with a `NullPointerException`, and a quiet answer would be a link the reader cannot follow.
- `MarkdownNodes.next` owns the document-order walk. Each caller keeps its own node budget and its own message, because the budget belongs to what the caller is doing.
- `StorageDirectories.requireContained` owns the directory isolation guard. Each walk keeps its own create step.
- `DeferredDownload` owns the stream that holds response headers uncommitted until the store produces its first byte. Each route passes only the headers it sets.
- `DocumentSearch` is a record: it validates the bounds in its compact constructor, answers whether a document matches, takes the requested window, and cuts the excerpt. Both callers keep their own corpus and their own result shape.
- `bridgeOperation` dispatches to one method per operation family. The four commands that reconcile the sandbox with the repository share the scope check, the capability check and the unresolved-write guards; each family keeps its own failure-code translation.

Two behavior changes are deliberate and neither is silent. Recursive deletion in the repository cache no longer follows a symlink when clearing the read-only flag. Public search gains the null checks the private API already had, which no request can reach because its controller declares `defaultValue = ""` for both strings.

Module structure is unchanged. The survey that preceded this work is recorded under Alternatives.

## Alternatives

Merging `spaces` into `workspace` was the cheapest structural change available: `spaces` is three files with one production consumer, and its integration test already lives under `workspace/internal/`. `ApplicationModules.verify()` rejects it with `Cycle detected: auth -> workspace -> auth`. `SpaceCreationService` needs `auth`, `content` and `workspace`, and the existing layering is `content -> auth -> workspace`, so `spaces` is a necessary composition layer rather than an accident. Reverted.

Removing the empty `qa` module was the other candidate, on the precedent of `projection` and `search` in the [stock PostgreSQL note](2026-09-05-stock-postgresql.md). The [requirements record](2026-08-25-requirements-and-architecture.md) disagrees: it carries a "Deferred visitor Q&A design" section with budget reservation, SSRF rules and a rendering pipeline, and names the capability a deferred product target. The removed modules had no such standing. `qa` stays.

Splitting `content` is the largest structural change available and remains open. It holds more than half the production code, and its top level carries distinct capabilities that are natural seams: document identity and writes, repository authority and transport, Markdown and media indexing, moves, portable exports, publication snapshots. It needs its own decision record and its own review, and it collides with every open branch, so it is best done when few are open.

Splitting the remaining 33 over-limit methods one at a time was rejected as the wrong unit of work. Length is a symptom; a method split in half to clear a gate is two methods that still say the same thing twice.

## Consequences

`src/main` grows from 212 files to 220 and from 24,244 lines to 24,371. The added lines are the new owners and their documentation; the removed lines are the copies. Fewer lines was not the goal and is not the result. One place to change a rule is.

The exemption register in `config/checkstyle/xpath-suppressions.xml` falls from 37 named methods to 33. `bridgeOperation` left it at 26 code lines. `exportPackage`, `open` and `result` had already been made stale by the worker-response records and were removed with the gate confirming it.

A security guard written three times could be fixed in two of them. After this change the directory isolation guard, the recursive delete and the strict decoders each fail or pass in one place.

`DocumentSearch` is public API in `content`. A future corpus that wants the same search semantics constructs one rather than copying six bounds.

The file-mode question has two named answers rather than five spellings of one. No inline mode check remains: the only surviving literal use of `FileMode.REGULAR_FILE` outside `RepositoryBlobs` is the patch service setting it when it writes the media index, which is what makes the stricter reader rule hold.

## Verification

- `./gradlew test checkstyleMain checkstyleTest checkstyleIntegrationTest spotlessCheck` passes: 472 unit tests including the module-boundary suite, and Checkstyle on all three source sets with four fewer exemptions.
- The two relative-link algorithms were compared over 48,205 generated path pairs of the shapes both callers produce. They agree on every pair except where the target is the document's own directory, which cannot occur because a target is always a file.
- Integration and acceptance tests were not run locally; Docker is unavailable on the authoring machine. CI runs them in the `verify` job.
- `WorkerSocketTests.aNewWorkerBootRecoversSaturatedAdmissionWithoutReopeningTheOldClient` fails intermittently under load and passes when its class runs alone. Two consecutive full runs failed; later full runs passed both with and without the change, and the class passes alone every time. The production code changed in those runs was in `content`, which cannot reach a worker socket. Tracked separately.
