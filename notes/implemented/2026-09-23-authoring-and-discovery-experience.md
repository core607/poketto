# Authoring and discovery experience

Date: 2026-09-23

## Problem and scope

The source editor exposed saves, previews and atomic moves, but publishing required
understanding storage paths. An interrupted tab lost unsaved text, and image
insertion required a separate file-selection flow. Uniform discovery samples
provided neither a following entrance nor authored selection signals.

The browser provides draft, preview, publish and withdraw actions; image paste and
drop; local draft recovery; and following/discovery entrances with bounded recommendations.
The Markdown source editor remains available. This decision does not add a second
content authority, rich-text editor, cloud draft store or behavioral tracking.

## Authoring actions

Drafts start under `private/`. Saving a draft uses the ordinary optimistic text
patch. Preview keeps the existing authenticated renderer and exact media grants.
Publish and withdraw move a saved note between matching `private/` and `public/`
paths, preserving category paths and article identity. They use the
[atomic move service](2026-09-09-atomic-content-moves.md), including
reference repair, collision refusal and private dependency checks. Unsaved text
must be saved first; a move never discards it or silently creates two commits.

The interface names the requested visibility action and resulting location before
confirmation. Moving into public storage does not enable the website or override
an exclusion, invalid policy or administrator restriction. The current public-page
state determines whether the interface reports a live page, an unpublished saved
file or a restriction requiring attention. Website settings remain a separate,
explicit owner action. Advanced path and folder controls remain available.

## Image paste and drop

Pasted or dropped supported image files enter the same bounded, authenticated
upload path as file selection. Upload success inserts a reference into the current
draft, without saving or publishing it. File size and media validation remain
server-owned; local checks provide early feedback. Ordinary text paste is unchanged.

Each selected upload retains one idempotency key across uncertain retries. A late
reply cannot insert into a different file, workspace or unmounted editor. While an
upload is in progress, navigation must preserve the existing draft and clearly
handle the unfinished insertion. Unsupported files and partial failures remain
visible rather than being silently dropped. No external image URL is fetched.

## Local draft recovery

Unsaved text is retained as plaintext in this browser, scoped by account, workspace, path
and draft identity. Retention includes the loaded commit, expected revision or
absence, edited source and timestamp. It is recovery state, never an authoritative
repository version. Bound each draft by the ordinary document limit, the browser
store to 20 drafts and total encoded content to 2 MiB. Quota or storage failures
keep editing available and display that recovery could not be updated; they must
not evict another unsaved draft silently.

Recovery is offered only after the current account and normal file read establish
access. It never automatically applies or saves cached text. A changed remote file
retains the original conflict preconditions; recovery cannot adopt a newer revision
and overwrite intervening edits. Matching already-saved source clears the redundant
draft. Explicit discard, successful save and explicit logout clear the appropriate
recovery records. Separate tab drafts cannot overwrite each other's cached edits.
Returning to the saved source clears the current editing record; intentionally
emptying a new draft retains its latest state. Web Locks serialize bounded writes,
and a logout generation prevents stale tabs from recreating cleared records.
Writes coalesce after 250 ms without input and at most one second during continuous
typing. Visibility loss and pagehide attempt an earlier flush; explicit discard
and unmount cancel pending work. Abrupt termination can lose pending edits, so the
interface distinguishes a pending write from a retained recovery record. Count
and byte limits are shared by the browser's accounts and spaces; scoped reads
remain available even when foreign records fill the store. Capacity feedback names
account/space cleanup and the data-loss consequence of clearing browser site data.
These records do not sync to another device and are lost if browser storage is
cleared. Access to the browser profile can expose their plaintext.

## Following and discovery

The homepage exposes discovery and following as distinct views, with a direct
entrance to private bookmarks. Following reuses the chronological, account-scoped
[community feed](2026-09-23-community-interactions.md). It does not
mix personal follow data into public responses or recommendation caches.

Discovery retains [stable batches](2026-09-14-public-discovery-batches.md),
their current-publication checks, expiry and count/text budgets. Selection uses
four slots within each bounded space sample: one optional authored
`featured: true` choice, one most-recent article by its existing creation date, one
article contributing the most unseen tags, and random remaining content. Ties use
reservoir sampling; absent featured choices leave another random slot. Selection
retains only four article references and scans the existing public snapshot. Only
a boolean enables the optional featured field; malformed values leave it disabled.
A featured signal is an author's selection for their own space, not a site
endorsement. A space contributes at most four cards regardless of its volume or
featured flags. Randomized
selection among remaining candidates keeps older and differently tagged content
discoverable. Optional tag selection is public, explicit, exact and retained with the batch.
Continuation inherits that tag; a conflicting tag on replay or continuation is
rejected, and changing the selection starts a new batch. The retained tag counts
toward the existing text budget.

There is no model ranking, private reading-history profile, database article copy
or paid placement. Article cards still resolve against current publication before
emission; withdrawal and owner restrictions override every selection signal.

## Alternatives and consequences

A separate draft/publication database would duplicate Git state and require another
reconciliation contract. Directly toggling metadata would bypass the directory and
dependency rules. The existing save and move services keep those boundaries visible.

Automatic recovery into the newest file baseline can overwrite work saved by another
client. Explicit recovery with retained preconditions preserves conflict detection.
Browser recovery is simpler than a cloud draft service, but is device-local and
must explain retention and clearing to the author. Image insertion shares upload
semantics so uncertainty does not create a second retry mechanism.

Full recommendation profiling is disproportionate to the current content scale.
Authored selection, tags, recency and per-space diversity make the initial behavior
inspectable while retaining bounded random discovery. This supersedes only the
uniform selection algorithm in the batch record, not its replay,
withdrawal, isolation or capacity contract.

## Verification and related decisions

Chrome acceptance against the isolated Spring/PostgreSQL/Git/Next.js entrance
verifies private creation, explicit restoration after reload, cross-tab revision
conflicts without overwriting the saved file, real PNG clipboard upload and preview,
save/publish/withdraw with stable article identity, tag-filtered batches and their
continuations, withdrawal from an existing batch, following, direct private bookmarks,
workspace isolation, logout cleanup and stale-tab refusal. The refreshed frontend
also verifies cleanup after returning to the saved source. Desktop and 390-pixel
layouts retain usable discovery controls; anonymous following requires login.

Mounted component tests cover paste and drop events, uncertain upload retries
under one idempotency key, late acknowledgements after unmount, permission revocation
before recovery, publication restrictions, undo cleanup and intentionally empty
new drafts. OS file-manager drag was not exercised in Chrome. Storage tests cover
multiple accounts/tabs, count and multibyte quotas, malformed records and browser
storage failures. Selection tests cover authored, recent, diverse and older content;
real PostgreSQL/HTTP tests verify tag-bound replay and mismatched-tag rejection.
Unit, style, frontend production-build and Linux storage gates cover adjacent
interfaces. Synthetic acceptance does not claim production-corpus scale or new
provider interoperability.

The [browser interface](2026-09-06-blog-browser-interface.md),
[CodeAct content contract](2026-09-09-codeact-content-and-media.md),
atomic move record, discovery batch record and community record retain ownership
of their existing mechanisms. This decision extends their user entrances and
supersedes no permission, repository ownership or media-delivery rule.
