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

- **Discovery** (`/`, `/search`): a thin global bar holds the brand, discovery,
  search and an account menu (studio, notifications, bookmarks, followed spaces,
  account, site administration for administrators, sign-out). The home page opens
  with a search field, offers tag chips from the current batch beside the exact-tag
  form, and shows whole clickable cards. Following is a segment of the same page;
  anonymous readers get a sign-in prompt.
- **Space sites** (`/s/{slug}`): a masthead with the space name, record count and a
  follow action, and the space's own tabs (home, tags, archive, search). The home
  tab shows the root note in an intro panel above the article list; the archive is
  grouped by year. Root `/read`, `/tags` and `/archive` redirect temporarily to the
  default space, because the default space can change.
- **Reading**: one measured column with breadcrumbs from the space; collections sit
  in a side rail on wide screens and inline on narrow ones, with previous/next links
  at the end of an article read through a collection. Interactions render only for
  articles with an identity; other articles offer following their space. Anonymous
  readers sign in through a dialog without leaving the article, and the global bar
  picks up the new session.
- **Studio** (`/admin`): an application shell with a sidebar. A filterable space
  switcher shows the current space and role; the space sections (content, website,
  members, access keys, connected apps, repository) follow, then account and, for
  administrators, site administration. Section URLs, the unsaved-draft guard and
  history positions are unchanged. The account section opens with a profile card and
  groups creating, joining and manually connecting spaces.
- **Editor**: a file sidebar (creation, filename search, local drafts, repository
  tree, body search, advanced path, diagnostics), a sticky toolbar with the path,
  public or private root, save state and actions, a segmented view switcher, and
  the media picker. Tree rows reveal move and export on hover or focus.
- **Personal** (`/community`): notifications, bookmarks, likes, followed spaces, the
  following feed and blocks. `?tab=` selects any personal list. The report queue is
  part of site administration together with site account management.

### Visual language

Warm paper and ink with an amber accent; the mark is a pocket holding an amber page,
also served as the site icon. Interface text uses the platform sans stack; display
titles use a Latin serif (Iowan Old Style, Palatino, Georgia) before the CJK serif.
Colors, type scale, spacing, radii and elevation are custom properties in
`frontend/app/styles/base.css`, with a warm dark theme that follows the system
preference. Styles are split into base, controls, public places, studio and editor
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

## Delivery and verification

The remake shipped in three steps: the design system, chrome and public places; the
studio shell, personal lists, site administration and editor, which retired the
scoped legacy stylesheet; then the dark theme and narrow-screen polish. Each step
passed `./gradlew frontendCheck` and the frontend contract tests, which were updated
only where routes or wording deliberately changed. Each was exercised in the
isolated browser entrance with this frontend at 1440 px and 390 px, anonymous and
signed in as the fixture owner, and the dark theme was captured for discovery, a
space, a collection article, sign-in and the editor.

## Gaps

- The theme follows the system preference; there is no manual switch.
- The space masthead has no description: the public space API returns only a slug
  and a display name.
- `/rss.xml` still covers only the default space; space sites have no feed.
- Tree row actions rely on hover or keyboard focus; on touch screens they appear
  once a row's button has focus.
