# Frontend Remake

Date: 2026-09-23
Status: Implemented

## Problem

The browser interface grew one feature at a time around the original single-space
blog. Poketto is a multi-account content workspace: each space is one Git
repository with its own website, the root site samples published spaces, readers
follow, bookmark and comment, authors work in a file-based editor, agents connect
over MCP, and site administrators moderate accounts and content. The interface did
not express that shape:

- One global navigation served every place. Its article, tag and archive links
  opened the default space even on another space's site, and administration shared
  the reading header.
- `/admin` mixed account settings, space settings and site administration in one
  row of tabs under a paginated native space `<select>`.
- `/community` mixed personal lists with the site-wide report queue.
- Readers saw implementation states such as "this article has not enabled
  interactions" and a disabled follow button with a disclosure.
- Every list printed previous/next controls and "page 1 of 1"; internal links
  carried the external-link arrow; Latin headings fell back to a monospaced serif on
  Windows; one 1,850-line stylesheet restyled bare elements.

## Decision

The presentation layer is rebuilt around the places the product has. Spring APIs,
route contracts, write semantics, local drafts, conflict handling and the restricted
Markdown renderer keep their behavior; layout, navigation, visual language and
component structure are new.

### Places

Each place has its own navigation; [frontend/README.md](../../frontend/README.md) lists the routes.

- **Discovery** (`/`, `/search`) sits under a thin global bar holding the brand, discovery, search and an account menu; following is a segment of the same page.
- **Space sites** (`/s/{slug}`) carry the space's own tabs and RSS feed, so links on one space's site never open the default space. Root `/read`, `/tags` and `/archive` redirect temporarily to the default space, because the default space can change.
- **Reading** shows interactions only for articles with an identity; other articles offer following their space. Anonymous readers sign in through a dialog without leaving the article.
- **Studio** (`/admin`) is an application shell whose sidebar separates space sections, account and site administration. Section URLs, the unsaved-draft guard and history positions keep the [content navigation](2026-09-14-admin-content-navigation.md) contract.
- **Personal** (`/community`) holds only personal lists; the report queue belongs to site administration with account management.

### Visual language

Warm paper and ink with an amber accent; the mark is a pocket holding an amber page,
also served as the site icon. Interface text uses the platform sans stack; display
titles use a Latin serif (Iowan Old Style, Palatino, Georgia) before the CJK serif.
Colors, type scale, spacing, radii and elevation are custom properties in
`frontend/app/styles/base.css`, with a warm dark theme that follows the system
preference unless the reader picks light or dark in the footer or account menu; a
nonce-carrying head script applies that stored choice before paint. Styles are split into base, controls, public places, studio and editor
files; components style themselves with classes, and studio element defaults use
`:where()` so component rules always win. Fonts, icons and tree chevrons are local:
the content security policy allows neither remote fonts nor `data:` images.

### Interface rules

- Pagination appears only when a previous or next page exists.
- The external-link arrow marks only links that open a new tab or leave Poketto.
- Empty, loading and error states name what happened and the next action.
- Destructive actions keep their confirmation dialogs.
- Every page works at 360 px without horizontal scrolling; on narrow screens the
  studio navigation scrolls in its own strip and the file sidebar is height-capped.

## Alternatives

A component library (Radix, shadcn/ui) would add dependencies and conventions for a
small set of controls the application already implements with accessible native
elements. Restyling the existing pages in place would have kept the single
navigation and the mixed administration tabs, which were the main problems. A
separate administration application would duplicate authentication, drafts and API
clients.

## Verification

`./gradlew frontendCheck` and the frontend contract tests pin routes and wording. Layouts were checked in the [isolated browser entrance](../../acceptance/README.md) at 1440 px and 390 px, anonymous and signed in, in both themes.

## Gaps

- Discovery covers need a readable public image; articles that only link external
  images, or whose images are private, show no cover.
