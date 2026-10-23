# Private Reading Activity

Date: 2026-10-23

## Problem

Requiring community eligibility for private bookmarks and follows leaves registered
readers without a way to keep what they find. These records neither publish content
nor expose the reader to space owners.

## Decision

An active browser account may bookmark and follow currently published content.
Likes, comments and corrections retain community eligibility; creating spaces and
public websites retains creator eligibility. This partially supersedes the private
activity gate in [community interactions](2026-09-23-community-interactions.md)
and [consumer identity](2026-09-20-consumer-identity-and-site-policy.md).

## Alternatives

Keeping manual approval for private activity adds an admission step without limiting
public expression. Automatically granting COMMUNITY would also authorize likes and
comments. Neither matches the distinction between personal records and publication.

## Consequences

Current publication checks, account suspension, per-account ownership, frequency
limits and retained-record bounds still apply. Group downgrade preserves private
activity without granting workspace access or machine authority.

## Verification

`CommunityIntegrationIT` and `frontend/tests/community.test.tsx` cover the boundary.
