# Frontend Remake

Date: 2026-09-23
Status: Implemented

## Problem

A single navigation mixed public reading, personal records, space authoring and
site administration. Links could open the default space instead of the current
one, while global element styles made local controls unpredictable.

## Decision

Each context owns its navigation within one Next.js application.
[The frontend reference](../../frontend/README.md) owns routes and rendering;
[content navigation](2026-09-14-admin-content-navigation.md) owns studio URLs,
draft guards and browser history. Account settings and site administration are
independent of selecting a workspace; personal lists exclude site-wide reports.
Layout changes retain Spring authorization, atomic writes, draft recovery,
conflict handling and restricted Markdown rendering.

[Design tokens](../../frontend/app/styles/base.css) own colors, type, spacing
and elevation. Platform sans text and Latin-serif-before-CJK headings avoid the
Windows monospaced fallback. Components use classes; low-specificity `:where()`
studio defaults cannot override them. Base, controls, public, studio and editor
styles have separate owners. Theme follows the system unless explicitly selected;
a nonce-bearing head script applies the stored choice before paint. Fonts, icons
and chevrons stay local because CSP excludes remote fonts and `data:` images.

Pagination appears only when another page exists. External arrows identify new-tab
or off-site links. Empty, loading and error states name the next action.
Destructive actions retain confirmation. Layouts accommodate 360 px without page
overflow; narrow studio navigation scrolls independently and file lists are
height-capped.

## Alternatives

Radix or shadcn/ui adds dependencies for controls already supported by accessible
native elements. Restyling mixed tabs retains the navigation problem. A separate
administration application duplicates authentication, drafts and API clients.

## Consequences and verification

Context-specific navigation shares the existing application and authority
boundaries. `frontendCheck` and frontend contract tests verify routes and controls.
The [isolated browser entrance](../../acceptance/README.md) verifies desktop and
mobile layouts, both themes, and anonymous and signed-in flows. Discovery covers
still require a readable public image; external-only or private images supply none.
