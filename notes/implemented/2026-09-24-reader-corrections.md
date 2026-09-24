# Reader corrections

Date: 2026-09-24

## Problem

A reader who spots a typo or a wrong figure can only describe it in a comment. The author then edits the file by hand. Because every space is a Git repository with an optimistic write path, a proposed change could be reviewed as an exact diff and applied as a commit. The [community decision](2026-09-23-community-interactions.md) holds that interactions must never edit source automatically, so any change still needs an explicit acceptance by someone allowed to publish.

## Decision

**Proposing.** Below a public article, a signed-in account in the community group or above opens 「建议修改」, edits the served body in a text area and adds an optional reason of up to 500 characters. [CorrectionProposal](../../frontend/components/correction-proposal.tsx) sends `POST /api/auth/community/spaces/{slug}/corrections` with:
- the route;
- `baseDigest`, the SHA-256 of the UTF-8 body the reader started from;
- the proposed body, non-blank and at most 1 MiB, with the article's CRLF line endings restored when the served body uses them;
- the reason;
- whether the proposer may be named once accepted, checked by default. The dialog states that naming means both the footer credit and the account id in the commit trailer.

[CommunityCorrections](../../src/main/java/io/github/core607/poketto/community/internal/CommunityCorrections.java) refuses the proposal unless:
- the route is public in the current snapshot;
- the digest matches the served body, otherwise it answers `COMMUNITY_BASE_CHANGED` (409);
- the body differs from the base;
- neither the proposer nor a space owner has blocked the other;
- the proposer has no other open proposal for the same article (409).

Proposals count against 5 a minute and 30 a day per account in `community_rate_limits`. Only browser account sessions reach these routes; API keys and connection tokens cannot propose. The request body limit for this route is 3 MiB, enough for a 1 MiB body after JSON escaping; other community bodies stay at 64 KiB.

**Storage.** `community_corrections` (migration `V22`) stores the route, the proposer, `base_digest`, `proposed_body`, `reason`, `credited`, a status of OPEN, ACCEPTING, ACCEPTED, DECLINED, WITHDRAWN or STALE, the creation, claim and resolution times, `resolver_id` and the accepting commit. Routes key corrections because most articles have no frontmatter `id`; the base digest prevents applying a proposal to different text.

**Notifying.** A `community_notifications` row now references either a comment or a correction event. Space owners receive PROPOSED; the proposer receives ACCEPTED, DECLINED or STALE. The comment rules apply: the actor is never notified, blocked pairs are skipped, and each inbox keeps its newest 1,000 rows.

**Reviewing.** Members holding `PUBLISH` see a 「读者勘误」 section in the studio. [CorrectionReview](../../frontend/components/correction-review.tsx) shows each open proposal's reason and a line diff from the served body to the proposed body. A proposal whose base no longer matches the served body is marked stale.
- **Accept** calls [ReviewedBodyEdits](../../src/main/java/io/github/core607/poketto/content/ReviewedBodyEdits.java) as the reviewer. It:
  1. re-reads the served article's file at the remote head through the authorized reader;
  2. requires its current body to match `baseDigest`, answering STALE otherwise. When the body already equals the proposal, it writes nothing and answers already-applied with the commit it read; the proposal is recorded as accepted against that commit, and the reviewer is told no new commit was made;
  3. writes the original bytes before the body, including a byte-order mark and frontmatter, followed by the proposed body, through `RepositoryPatchService` with the file's exact revision;
  4. adds the trailer `Poketto-Suggested-By: account:<id>` after `Poketto-Principal`, only when the proposer allowed naming.

  A remote that moves during the write is re-read up to three times.

  The Git write cannot share a database transaction, because preparing GitHub credentials refuses to run inside one. Acceptance therefore first claims the proposal:
  - a conditional update moves it from OPEN to ACCEPTING for this reviewer;
  - after the write, a second update from this reviewer's ACCEPTING records ACCEPTED or STALE;
  - a failed write returns the proposal to OPEN.

  While a proposal is ACCEPTING, decline, withdrawal and a second acceptance answer `COMMUNITY_REQUEST_CONFLICT`. Any resolution whose update misses answers the same conflict rather than reporting success.

  A claim left for ten minutes, for example by a stopped process or a failure after the Git write, counts as open again everywhere:
  - it reappears in the review list;
  - the proposer sees it as open and can withdraw it;
  - a reviewer can decline it or accept it again.

  Repeating the write is safe, because a body that already equals the proposal is reported as applied without a new commit.
- **Decline** records the resolution without touching Git.
- A stale proposal can only be marked stale, which tells the proposer to start again from the current text.
- The proposer may withdraw an open proposal from the article page.

**Credit.** `GET /api/public/community/spaces/{slug}/corrections/credits?route=…` returns the display names of accepted proposers who allowed credit, earliest first and at most 20. Each proposal records the article it was made for: its frontmatter id and its creation time. Credit shows while the article served at the route matches either, so an article that gains an id, or loses a duplicate one, keeps its credit, while a new article at a reused route matches neither and starts without it. Credit is lost only when both change, such as an edited creation date on an article without an id. Proposals stored before these columns existed keep route-only credit. The article footer shows them as 「感谢 … 的勘误」. A proposer who has since blocked an owner, or been blocked by one, is left out, and so is an account whose profile is gone.

**Retention.** Proposal text and reasons are cleared once resolved for 90 days. The clearing runs whenever a new proposal is stored, through an index on resolution time limited to rows still holding text, so text can outlive 90 days on a space that receives no further proposals. The row stays for credit and notification history.

**Moderation.** Proposals are visible only to the space's publishing members, who decline them and can block the proposer. They are not reportable to site administrators. Unlike comments, their text never appears to other readers.

## Alternatives

- **A GitHub-style fork and pull request.** It requires readers to have Git accounts and repository access, and it bypasses Poketto's authorization.
- **Three-way merging stale proposals.** A merged result would be text the proposer never saw. STALE keeps each accepted change exactly as reviewed.
- **Allowing frontmatter edits.** Titles, routes, tags and identifiers carry publication and identity consequences that belong to members.
- **Letting proposals apply automatically after a timeout.** It violates the community decision on source ownership.
- **Reporting proposals to site administrators.** Only the space's reviewers ever see a proposal, and they can decline and block without help.

## Consequences and risks

- Proposal bodies duplicate article text in PostgreSQL until they are cleared.
- Spam proposals cost reviewers time. Rate limits, the one-open-proposal rule and blocking bound it.
- Credit and review read the website snapshot, so both are unavailable while the space's website is off.

## Verification

[CorrectionsIntegrationIT](../../src/integrationTest/java/io/github/core607/poketto/community/internal/CorrectionsIntegrationIT.java) pins the proposal, claim, stale, credit, notification and blocking rules against PostgreSQL; [RepositoryReviewedBodyEditsTests](../../src/test/java/io/github/core607/poketto/content/internal/RepositoryReviewedBodyEditsTests.java) pins byte-exact acceptance and the trailers against a real remote; [OriginAndBodyFilterTests](../../src/test/java/io/github/core607/poketto/auth/internal/OriginAndBodyFilterTests.java) pins the route's body limit; and [corrections.test.tsx](../../frontend/tests/corrections.test.tsx) pins the browser digest and review flow.
