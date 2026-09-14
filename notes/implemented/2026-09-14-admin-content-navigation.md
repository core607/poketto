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

This slice does not add repository-wide filename search or an authoritative public-page availability link. Those accepted requirements need their own bounded, authorized reads and remain part of the parent proposal.

## Verification

Focused frontend tests cover independent folder/document selection, save and move updates, stale editor responses, dirty Back/Forward cancellation, and repeated traversal during one confirmation. Creation tests verify private destinations, exact duplicate refusal, a new folder's ordinary `index.md` appearing only after Save, and retention of a draft when the server rejects a name collision. The complete frontend check includes type checking, formatting and the production build.

A real Spring/PostgreSQL/Next/Caddy fixture with two independent workspaces demonstrates directory/document navigation, cancelled Back/Forward without lost history, switching spaces, and a new note prepared below `private/` from a selected public category before its explicit save. Two browser tabs save case-fold-equivalent names and show the rejected tab retaining its body. The mobile run verifies the creation form's focus and no horizontal overflow. This evidence uses disposable local repositories; it does not establish production HTTPS or external MCP behavior.

The same-topic audit retains the [browser interface](../implemented/2026-09-06-blog-browser-interface.md), [authoring foundations](../implemented/2026-09-05-repository-authoring-foundations.md), [workspace routing](../implemented/2026-09-11-workspace-browser-and-mcp-routing.md), [directory navigation](../implemented/2026-09-08-repository-directory-navigation.md), [atomic moves](../implemented/2026-09-09-atomic-content-moves.md), [CodeAct content and media](../implemented/2026-09-09-codeact-content-and-media.md), and [member permissions](../implemented/2026-09-12-member-content-permissions.md) as independent owners of rendering, reads, writes, defaults and authorization. The parent delivery is verified by the [acceptance record](2026-09-15-multiuser-daily-use-acceptance.md); these subsystem decisions retain their independent guidance.
