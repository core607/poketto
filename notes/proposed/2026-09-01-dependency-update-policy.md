# Dependency and Toolchain Update Policy

Date: 2026-09-01
Status: Proposed

## Problem

Every external input to this build is pinned. The Gradle Wrapper, the Java language level, Spring Boot, Spring Modulith, and JGit carry exact versions. GitHub Actions are pinned by commit SHA with a comment naming the tag. The PostgreSQL base image is pinned by digest, and SCWS and zhparser are pinned to upstream commits.

The [development baseline](../implemented/2026-08-26-development-baseline.md) records that pinning every source makes upgrades deliberate. It does not say who proposes one, on what cadence, or against what evidence. Nothing in the repository raises an update, so a pin ages until a person happens to look, and a published security fix arrives on the same schedule as a cosmetic patch release, which is to say no schedule at all.

The pins are not one population. Declared library and plugin versions sit in a manifest a tool can read and compare against upstream metadata. Action SHAs and the base-image digest are machine-readable but only meaningful together with the comment that names the human-readable version. SCWS and zhparser are pinned to commits on projects that publish no release cadence, so no tool can judge whether moving is an improvement, and moving them changes Chinese tokenization — the behavior the integration suite exists to protect.

Treating those three populations identically produces either an unreviewable stream of proposals or no proposals at all.

## Proposal

### Automated proposals for machine-readable pins

An update bot opens pull requests for Gradle dependencies and plugins, the Gradle Wrapper, GitHub Actions, and the PostgreSQL base-image digest. Action updates keep SHA pinning and refresh the accompanying version comment; a proposal that replaces a SHA with a tag is a misconfiguration, not an update.

Every proposal is an ordinary pull request and passes `check` on the same terms as human work. No update path merges without that gate, and none bypasses review.

Routine updates are grouped and land on a fixed cadence so review arrives in batches rather than continuously. An advisory with a published fix is proposed on its own, immediately, because batching a security fix behind unrelated version bumps delays it for no benefit.

### Pins that stay manual

SCWS and zhparser are proposed by a person. Their upstreams publish commits rather than releases, and the effect of moving is a change in tokenization rather than a changelog entry, so the proposal carries the reason for moving and evidence that tokenization still behaves as the integration suite expects.

The Java language level and the major versions of Spring Boot and Spring Modulith are architecture decisions. They arrive as decision records with their own alternatives and consequences, not as a bot's pull request title.

### What an update must prove

A green `check` is the floor, and for most library updates it is also the ceiling. An update that touches content serialization, git behavior, or tokenization needs evidence from the tests that own that behavior, because a build that compiles and passes unrelated suites proves nothing about a canonical byte sequence or a commit graph.

A declined update closes with its reason recorded, so the same proposal is not reopened and re-evaluated from nothing on the next cadence.

### When the volume exceeds the review capacity

If proposals arrive faster than they can be reviewed, the response is a longer cadence or tighter grouping. Disabling the gate, merging without review, or letting proposals accumulate unread are not responses; each converts a visible queue into an invisible one.

## Implementation scope and dependencies

This proposal depends on the implemented [development baseline](../implemented/2026-08-26-development-baseline.md) for the build and the CI gate.

The first implementation includes the bot configuration covering each automated pin family, the grouping and cadence, the recorded manual list, and the documented review expectation.

It excludes automatic merge, license scanning, container vulnerability scanning, SBOM publication, provenance attestation, and any change to what `check` verifies.

## Alternatives considered

**Continue updating by hand with no schedule.** It needs no configuration and produces no review queue. Pins age invisibly and a security fix waits for someone to look, which across four pin families and a growing dependency set is a matter of when rather than whether something is missed.

**Remove the pins and track version ranges.** The maintenance queue disappears entirely. So does reproducibility: a failing integration run could come from a build that changed underneath the branch rather than from the change under review, which is the property the baseline pinned everything to keep.

**Merge green updates automatically.** Review load drops to zero and updates land promptly. A green `check` does not establish that a tokenizer, a serialization format, or git behavior survived the change, and the repository's own rules place merge authority with a person.

**Adopt a dependency lockfile and nothing else.** A lockfile makes resolution exact and reviewable. It records what is currently used without saying anything about when to move, so the aging problem is unchanged.

**Let the bot cover SCWS and zhparser too.** Coverage would be uniform and nothing would be forgotten. A commit-pinned dependency with no release metadata gives a bot no signal beyond "newer", so it would propose movement without evidence on exactly the two pins whose effect is hardest to review.

## Acceptance

- Every pin family is either covered by the bot or named on the manual list. None is unassigned.
- A bot pull request runs the same `check` as human work and cannot merge without passing it.
- An action update leaves the SHA pinned and the version comment consistent with the new SHA.
- An update affecting tokenization, content serialization, or git behavior carries evidence from the owning tests, not only a green build.
- A security advisory with a published fix produces its own pull request rather than waiting for the routine batch.
- A declined update records why it was declined.

## Risks

Update pull requests compete with feature work for the same review attention. Cadence and grouping are the available levers; leaving proposals unread is not one, because an unread queue is indistinguishable from having no policy.

A pinned digest or upstream commit can disappear when a registry entry is deleted or a repository is rewritten. The build then fails reproducibly rather than silently, which is the safer failure, but recovering needs a known path to select and justify a replacement pin.

Bot pull requests do not reach the model review described by [API pull request review CI](../implemented/2026-09-01-api-pr-review-ci.md), because that workflow runs only for pull requests the repository owner authored. That is the intended cost boundary, and it means an update proposal receives deterministic checks and human review only.
