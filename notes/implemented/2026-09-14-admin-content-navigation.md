# Administration Content Navigation and Creation

Date: 2026-09-14

## Problem

The [multi-user administration contract](2026-09-11-multiuser-workspaces-and-discovery.md) requires restorable space, tab, folder and document navigation with private creation defaults. The workspace dashboard retained space and tab and confirmed discarded edits, but the editor only consumed the initial document path. Opening or moving a document did not update that URL, and a directory could only be expanded rather than selected as a creation context.

## Decision

The dashboard owns updates to `workspace`, `tab`, `folder` and `path` in the administration URL. Opening a document or selecting a folder adds a navigation entry when the location changes. Initial restoration and a successful save or move normalize the current entry. Switching spaces clears the previous document and folder; tab changes preserve them. The dashboard confirms Back, Forward, tab and workspace changes. Its history entries retain an index so cancelling traversal returns to the accepted entry without overwriting the destination or losing Forward history. An editor that has unmounted cannot change the new workspace's URL when an old request finishes.

Selecting a directory changes creation context while retaining the open editor and its unsaved text. Opening another file or preparing a new draft still confirms discarding edits. Opening or saving a file retains the selected folder; preparing a new draft selects its private destination. Moving a directory updates the selected folder only when it is inside the moved subtree. Directory expansion remains local presentation state; the selected folder and the open document restore independently from the URL. URLs restore repository content, not an unsaved body.

The sidebar offers New note and New folder using a single name. Creation keeps the category path within `private/`: a selected `private/notes` remains there, while `public/notes` creates under `private/notes`. The form shows its destination before submission. Members without private-write capability cannot use these default creation actions; the explicit full-path entrance remains available for authorized operations on other paths.

New note prepares an absent `.md` path, appending the suffix when omitted. New folder prepares its `index.md` landing because Git does not retain empty directories. Preparation refuses exact existing file or directory paths and only reads the current repository. Save performs the ordinary revision-checked patch, including the repository's Unicode-normalization and case-fold collision rules. A failed save retains the draft so its name or body can be corrected. A writable new draft counts as unsaved even before body text is entered. The UI explains that saving materializes the note or folder. No marker file, new storage authority or extra publishing flag is introduced.

## Alternatives and consequences

Restoring every query change inside the editor would compete with the dashboard's dirty confirmation. The dashboard keeps ownership of history traversal, and editor actions report their accepted location to it. Retaining a document path across workspace changes could address a different file in another repository, so space selection clears both path fields.

Creating an empty Git directory is not durable. A hidden placeholder would add a content convention that neither browsing nor reading needs; an ordinary folder landing reuses the existing [album and collection entrance](../implemented/2026-09-14-album-entrances-and-lightbox.md). Deferring its write to Save keeps creation within the editor's existing optimistic-write and error recovery flow.

Repeating Unicode case folding in the browser would introduce a second collision authority and still could not prevent a concurrent repository change. Draft preparation checks exact absence; the existing write validator makes the final collision decision.

Repository-wide [filename search](2026-09-14-administration-filename-search.md) and the [public-page availability](2026-09-14-editor-public-page-state.md) link use their own bounded, authorized reads.

## Verification

`frontend/tests/editor-navigation.test.tsx` and `frontend/tests/workspace-navigation.test.tsx` pin folder and document URLs, guarded Back/Forward traversal, stale editor responses and private creation.

Related: [browser interface](2026-09-06-blog-browser-interface.md), [workspace routing](2026-09-11-workspace-browser-and-mcp-routing.md), [atomic moves](2026-09-09-atomic-content-moves.md) and [member permissions](2026-09-12-member-content-permissions.md) own rendering, workspace selection, writes and authorization.
