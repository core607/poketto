# Saved Files and Public Page Availability

Date: 2026-09-14

## Problem

The [multi-user administration contract](2026-09-11-multiuser-workspaces-and-discovery.md) requires the editor to distinguish a saved Git revision from an available public page. The file response already carries publication scope, but the editor does not label it or provide an authoritative public-page entrance. A path below `public/` may be excluded by policy, have no valid article, belong to a disabled website, or wait for a current verified snapshot. A successful save alone cannot decide these states.

## Proposal

The authorized file response adds a public-page presentation state alongside the existing commit and publication scope. It reports an unsaved path, a private file, a disabled website, an unavailable page, or an available public article. Only the available case carries a space slug and logical route. The browser builds the canonical internal article URL from those fields.

Availability uses the selected file's exact commit and path, the current verified public snapshot and the current workspace website switch. It does not parse the draft, fetch Git again, load media or infer publication from a path prefix. Missing or expired snapshots keep file reading usable but report page availability as unknown or unavailable. Reading this file-level presentation does not permit a member to change the owner-only website settings.

The editor displays the authoritative scope and separates the saved/unsaved file state from the public-page state. View public page opens a separate tab so the current draft stays intact. When there are unsaved edits, the UI explains that the public page shows saved content. Preparing a draft or editing its target path does not publish it or claim that its destination scope has been verified.

After an acknowledged save, the editor retains the confirmed commit, revision and submitted text, then rereads file metadata at that exact commit. A metadata-read failure does not convert the acknowledged save into an unknown write outcome or invite replay. The UI keeps the saved draft and marks publication information as unconfirmed until a successful reread. Full readers may read the saved historical commit; public-only readers retain the current-main restriction. Moving a file uses its existing authoritative reread.

## Alternatives and consequences

Deriving availability from `public/` would bypass policy exclusions and the independent website switch. Linking every saved file would produce misleading entrances for binary or malformed documents. Replacing the editor with a later main revision after Save could silently discard the just-saved body. Exact-commit metadata reads retain that body's identity while checking public availability separately.

Availability is an observation, not a permanent delivery grant. The public page rechecks its normal authorization when opened; publication changes can withdraw it after the editor's observation. No second visibility flag, publishing authority, or content-format change is introduced.

## Acceptance

- Real authenticated HTTP file reads distinguish private, unsaved, website-disabled, unavailable and available article states. Available routes match the current exact workspace/commit/path, and stale or withdrawn content never receives an available entrance.
- An acknowledged save followed by failed metadata lookup retains the saved body/revision, reports publication as unconfirmed, and does not retry the write. A changed draft path does not inherit a stale availability link.
- Real desktop and mobile browser flows show private/public labels, saved versus unsaved status, a separate-tab public page and website shutdown without losing the draft. Existing filename search, folder navigation and dirty guards remain usable.

The same-topic audit retains [website delivery](../implemented/2026-09-14-workspace-public-delivery.md), [member permissions](../implemented/2026-09-12-member-content-permissions.md), [authoring foundations](../implemented/2026-09-05-repository-authoring-foundations.md), [logical routes](../implemented/2026-09-06-logical-repository-routes.md), and [atomic moves](../implemented/2026-09-09-atomic-content-moves.md) as independent owners. The parent proposal retains its remaining requirements.
