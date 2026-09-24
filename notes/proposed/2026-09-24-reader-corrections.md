# Reader corrections

Date: 2026-09-24
Status: Proposed

## Problem

A reader who spots a typo or a wrong figure can only describe it in a comment. The author then edits the file by hand. Because every space is a Git repository with an optimistic write path, a proposed change could be reviewed as an exact diff and applied as a commit. The [community decision](../implemented/2026-09-23-community-interactions.md) holds that interactions must never edit source automatically, so any change still needs an explicit acceptance by someone allowed to publish.

## Proposal

**Proposing.**
- A signed-in account in the community group or above opens 「建议修改」 on a public article, edits the served body in a plain text area and adds an optional reason of up to 500 characters.
- The request carries:
  - the space;
  - the route;
  - a SHA-256 digest of the body the reader started from;
  - the proposed body, at most 1 MiB and different from the base.
- The server refuses the proposal unless:
  - the route is public in the current snapshot;
  - the digest matches the served body;
  - the account is not blocked by the space owner.
- Limits: 5 proposals a minute and 30 a day per account, in the existing `community_rate_limits` window.
- Machine credentials cannot propose.

**Storage.** `community_corrections` stores:
- `correction_id`, `workspace_id`, `route`, optional `article_id`, `author_id`;
- `base_digest`, `proposed_body` and `reason`;
- `status`: OPEN, ACCEPTED, DECLINED, WITHDRAWN or STALE;
- the creation and resolution times, and `resolver_id`.

Routes key corrections because most current articles have no frontmatter `id`; the base digest prevents applying a proposal to different text.

**Notifying.** In-site notifications are extended so a row references either a comment or a correction. Space owners are notified of new proposals, with the same caps and blocking rules as root comments. The proposer is notified when the proposal is accepted or declined.

**Reviewing.**
- Members who may write the article's public path, meaning they hold `PUBLISH`, see open proposals in the studio.
- The review shows the reason and a line diff between the base and the proposed body, rendered with [lib/source-diff.ts](../../frontend/lib/source-diff.ts).
- **Accept**:
  1. Re-reads the current file at the remote head through the authorized reader.
  2. Requires its served body to still match `base_digest`.
  3. Writes the original frontmatter with the proposed body through `RepositoryPatchService.apply`, using the file's exact revision and the reviewer as principal.
  4. Adds a commit trailer `Poketto-Suggested-By: account:<id>`, which requires the patch API to accept extra trailers.
- A digest mismatch marks the proposal STALE and asks the proposer to start again from the current text.
- **Decline** records the resolution without touching Git.
- The proposer may withdraw an open proposal.

**Credit.** Accepted proposals show the proposer's community display name under the article as 「感谢 … 的勘误」, unless the proposer has since blocked the owner or deleted their records.

**Moderation.** The existing report and block rules apply to proposal text. Site administrators can review reported proposals without gaining repository access.

## Alternatives

- **A GitHub-style fork and pull request.** It requires readers to have Git accounts and repository access, and it bypasses Poketto's authorization.
- **Three-way merging stale proposals.** A merged result would be text the proposer never saw. STALE keeps each accepted change exactly as reviewed.
- **Allowing frontmatter edits.** Titles, routes, tags and identifiers carry publication and identity consequences that belong to members.
- **Letting proposals apply automatically after a timeout.** It violates the community decision on source ownership.

## Consequences and risks

- Proposal bodies duplicate article text in PostgreSQL. Resolved proposals are deleted 90 days after resolution, keeping the credit line and the notification history only.
- Spam proposals cost reviewers time. Rate limits, blocking and reports bound it.

## Verification plan

- Proposal refusals:
  - an unpublished route, a mismatched digest, an unchanged body or oversize text;
  - a blocked account or a machine credential.
- Acceptance:
  - it writes exactly the proposed body with the original frontmatter and the trailer;
  - a concurrent edit makes the proposal STALE;
  - a reviewer without `PUBLISH` is refused.
- Notifications reach owners and the proposer.
- The credit line appears after acceptance.
