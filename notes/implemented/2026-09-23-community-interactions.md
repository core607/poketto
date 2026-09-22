# Community interactions

Date: 2026-09-23

## Problem and scope

The [consumer identity policy](2026-09-20-consumer-identity-and-site-policy.md)
assigns community participation to the community group and above. Reading remains anonymous; participation
requires a current browser account in COMMUNITY, CREATOR, or ADMINISTRATOR. Space
membership alone never grants community participation, and machine credentials cannot
post as a person.

The community module provides likes, private bookmarks, space following, article comments with one level of
replies, in-site notifications, deletion, reporting, and blocking. A following feed is
chronological. Private messages, group chat, email notifications, recommendation ranking,
and account following are outside this decision.

## Content identity

An optional canonical UUID in Markdown frontmatter `id` identifies an article within its
workspace. New browser notes receive an ID. An author can prepare an ID for an existing
note in the editor and save it through the same optimistic patch operation as other text
changes. Preparation neither writes Git nor publishes a draft. Existing arbitrary Markdown
remains readable without an ID; article interactions require a valid, unique ID.

The ID travels with the source when a file moves or its route changes. A copy intended as
a different article needs a new ID. Missing, malformed, or duplicate IDs disable article
interactions without hiding otherwise valid articles. Ambiguous IDs never select the first
matching path. Fixing an ID reconnects its retained history; changing an ID deliberately
starts a different history. The workspace remains part of the key, so another workspace
cannot claim an article's interactions by copying its ID.

PostgreSQL holds interaction records, not an article projection. Cards and notification
targets resolve titles and routes from the current publication-approved snapshot. A
path-keyed table would detach history on renames; inferred Git rename similarity cannot
reliably distinguish moves from copies or rewrites. Automatically editing source when a
reader interacts would violate repository ownership and optimistic authoring. An optional
source ID makes this dependency visible and preserves external Git authoring.

## Participation and visibility

All public reads and new interactions require the space's current effective publication,
an unexpired snapshot, and a currently public article. Withdrawal, expiry, deletion, and
owner ineligibility hide comments, interaction entrances, and distribution cards together.
Stored history survives. Re-publication resolves current content rather than reviving an
old snapshot. Do not acquire a repository mutation lock from a snapshot callback.

Downgrade prevents new likes, bookmarks, follows, comments, and replies. An account can
still remove its own records, read available private history, mark notifications read,
report abuse, and manage its block list. Previous comments on someone else's published
article remain until independently removed. Authors' freely supplied article bylines are
labelled separately from commenters' authenticated account display identities; login names
and email addresses are never public community fields.

## Records and moderation

Likes and bookmarks are unique per account and article; follows are unique per account
and space. Add/remove operations are idempotent. Bookmarks and their owners are private,
and following does not expose a public follower roster. Feed and saved-item queries are
bounded and resolve only currently visible content.

Comments are plain text with at most 4,000 code points. A reply must target a live root
comment on the same article; replies cannot receive replies. A client-generated request
ID makes retries return the same comment and rejects reuse with different content. An
author may delete their own comment; a deleted root retains a tombstone for existing
replies and cannot receive new replies. Workspace owners and site administrators may
moderate published comments, without gaining private repository access.

Reporting records a bounded reason for administrator review; it does not automatically
remove a comment. Blocking hides the blocked account's comments and notifications for
the blocker and prevents new replies between the pair. It does not promise that public
content becomes inaccessible to an anonymous reader. Moderation can hide an entire
thread; hidden records are not returned through reply or notification endpoints.

Root comments notify the space owners; replies notify the root author. Do not notify the
actor or a blocked participant. Private bookmark and follow actions disclose no account
identity to space owners. Notifications are private, paginated, and can be marked read;
their targets obey the same current publication and moderation checks as comments.

Mutation payloads, page sizes, account activity rates, and retained relation counts have
server-enforced bounds. Idempotent retries consume neither another record nor another
notification. Current account authority and publication must be rechecked at the operation
boundary; transactions must not deadlock against account changes, workspace publication,
or snapshot installation.

## Delivery and verification

### Transaction and resource boundaries

Community writes acquire a shared site-policy lock, the acting account lock and a
shared workspace publication lock before entering the current snapshot callback.
The transaction commits inside that callback. Group changes, credential changes,
membership changes, website withdrawal and snapshot replacement therefore serialize
with the operation without acquiring repository authority from a snapshot callback.
The operation owns its transaction boundary and refuses an enclosing transaction.

Community records use opaque account and workspace UUID references rather than
cross-module foreign keys. Authority and existence are checked before insertion.
Foreign-key locks acquired while emitting an owner notification could otherwise
deadlock with that owner's credential recovery, which locks their account before
waiting for workspace revocation. Comment, reply, notification and report references
inside the community module retain database foreign keys. A future account or
workspace deletion operation must explicitly retire its community records.

Notifications retain the latest 1,000 entries per recipient and fan out to at most
100 current owners. A transaction-scoped advisory lock serializes each recipient's
notification insertion and trimming across spaces. Owner recipients are processed
in UUID order. This guard is separate from account locks to preserve credential
recovery ordering. Without it, concurrent spaces can each trim an older view and
commit more than 1,000 notifications. Paginated lists inspect 21 rows to return at most 20 entries;
hidden entries may leave an empty page with a continuation cursor. Unavailable
bookmarks and followed spaces have private removable placeholders without content
metadata. The following-space list is bounded to 100 entries. Current titles and
routes are resolved from publication snapshots, including after an article move.

Per-account retained limits are 1,000 likes, 1,000 bookmarks, 100 follows and 500
blocks. Fixed UTC windows bound new comments to 10/minute and 300/day, new relations
to 60/minute and 3,000/day, reports to 5/minute and 30/day, and blocks to 30/minute
and 500/day. Existing request identities and relations are checked before consuming
allowances. The feed admits two concurrent scans with 100,000-document and five-second
bounds, checked between bounded workspace scans. Request bodies allow 64 KiB to
accommodate a 4,000-code-point comment even when JSON escapes surrogate pairs.

### Acceptance

The identity suites exercise real Git save, move, route-change, duplicate and conflict
paths. Database integration tests cover uniqueness, retries, reply depth, moderation,
private-list isolation, downgrade, withdrawal, recovery, inbox retention and concurrent
credential recovery. HTTP acceptance covers session/CSRF enforcement, private-list
isolation, 4,000-code-point comments and the no-write identity preparation endpoint.

The [browser entrance](../../acceptance/README.md) was exercised with separate author,
community, viewer and administrator fixture accounts. It confirmed saved identity
preparation; likes, private bookmarks and following; comments and replies; notifications
and read state; report handling and blocking; article moves; website withdrawal and
recovery; downgrade and removal of unavailable records. Article bylines remain distinct
from account display identities. The additive migration preserves existing accounts,
workspaces and grants. These synthetic fixtures do not claim real-provider acceptance;
community interactions require no additional external provider.
