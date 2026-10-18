# Holder-issued keys act for the account

Date: 2026-10-18

## Problem

Plaza account actions read and change account-wide state: pocket notes, candy and
wishes, signed comments and game saves. Per-key grants made every holder enable four
switches for each of their own keys before an agent could use the account. Their
only protective use was a key a space owner issues for another member, which must
not act as that member.

## Decision

A key acts for its holder's account exactly when the holder issued it: directly,
through another of their keys, or by approving OAuth. The test is
`auth_api_keys.created_by = account_id`, exposed as `MachineAccounts.Identity.personal`.
Other keys still read public pockets, while account actions return
`PERSONAL_KEY_REQUIRED`. Membership, creator eligibility, revocation and
`POKETTO_PLAZA_INTERACTIONS_ENABLED` are still checked on every command, and
revoking a key discards a game move or wish it is running. This partially
supersedes the [plaza foundation](2026-10-09-plaza-foundation.md).

## Alternatives

Grants enabled by default keep four switches for a case that issuance already
identifies. Letting every key act for its holder lets a space owner use a member's
account, which the plaza foundation rejected. One switch per key still costs a step
for each key.

## Consequences

Account authority starts at issuance and ends at revocation, and a key cannot have
repository access without account access. Members cannot issue keys directly, so
their agents act for them only through OAuth. `V28__drop_machine_account_grants.sql`
drops the grant table.

## Verification

`PlazaIntegrationIT`, `MachineCommunityIntegrationIT`, `QaIntegrationIT` and
`GameSavesIntegrationIT` pin both kinds of key.
