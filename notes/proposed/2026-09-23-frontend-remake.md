# Frontend Remake

Date: 2026-09-23

## Problem

The browser interface grew one feature at a time around the original single-space
blog. Poketto is now a multi-account content workspace: each space is one Git
repository with its own website, the root site samples published spaces, readers
follow, bookmark and comment, authors work in a file-based editor, agents connect
over MCP, and site administrators moderate accounts and content. The interface does
not express that shape:

- One global navigation serves every place. Its article, tag and archive links
  open the default space even on another space's site, and administration shares
  the reading header.
- `/admin` mixes account settings, space settings and site administration in one
  row of tabs. The space selector is a paginated native `<select>`; site account
  management sits beside GitHub space creation and invitation codes.
- `/community` mixes personal lists with the site-wide report queue.
- Readers see implementation states such as "this article has not enabled
  interactions", and a disabled follow button with a disclosure instead of a login
  link.
- Every list prints previous/next controls and "page 1 of 1". Internal links carry
  the external-link arrow. Latin headings fall back to a monospaced system serif on
  Windows. One 1,850-line stylesheet restyles bare elements, so every new control
  inherits grid layout and dark button styling.

## Decision

Rebuild the presentation layer around the places the product actually has. Spring
APIs, route contracts, write semantics, local drafts, conflict handling and the
restricted Markdown renderer keep their current behavior; the remake replaces
layout, navigation, visual language and component structure.

### Places

- **Discovery** (`/`, `/search`): a thin global bar with the brand, discovery,
  search and the account entrance. The home page opens with a short greeting and
  a search field, offers tag chips drawn from the current batch, and shows whole
  clickable cards. Following and bookmarks are segments of the same page for
  signed-in readers and a sign-in prompt for anonymous ones.
- **Space sites** (`/s/{slug}` and the default space's root routes): a masthead
  with the space name, public signature and a follow action, and the space's own
  navigation (home, tags, archive, search). The global bar stays but never links
  into another space's lists.
- **Reading**: one measured column; breadcrumbs from the space to the folder;
  collections as a side rail on wide screens and an inline strip on narrow ones;
  interactions and comments below the article. Interaction entrances appear only
  when the article supports them; the reason they are unavailable is shown to the
  author in the editor, not to readers.
- **Studio** (`/admin`): an application shell with a sidebar. A searchable space
  switcher sits at the top; space sections follow (content, website, members,
  access, repository); account and site administration are separate sections
  below. Content keeps the file tree, editor and preview, with row actions in
  menus and one status indicator for saved, unsaved, conflict and public state.
- **Personal** (`/community`): notifications, bookmarks, likes, followed spaces and
  blocks. The report queue moves to site administration.

### Visual language

Warm paper and ink with an amber accent. Interface text uses the platform sans
stack (PingFang SC, Microsoft YaHei UI, Segoe UI); display titles use a Latin serif
before the CJK serif so mixed titles do not fall back to a monospaced face. Colors,
type scale, spacing, radii and elevation are CSS custom properties with a dark
theme that follows the system preference. Components use explicit classes; bare
element selectors set only typography and resets. Fonts and icons are local: the
content security policy allows neither remote fonts nor scripts.

### Interface rules

- Pagination appears only when a previous or next page exists.
- The external-link arrow marks only links that leave Poketto or open a new tab.
- Empty, loading and error states name what happened and the next action.
- Destructive actions keep their confirmation dialogs; the dialogs share one
  component and name the affected object.
- Every page works at 360 px wide without horizontal scrolling.

## Alternatives

A component library (Radix, shadcn/ui) would add dependencies and styling
conventions for a small set of controls the application already implements with
accessible native elements. Restyling the existing pages in place would keep the
single navigation and the mixed administration tabs, which are the main problems.
Moving administration to a separate application would duplicate authentication,
drafts and API clients.

## Delivery and verification

Deliver in reviewable steps, each deployable on its own: the design foundation,
global chrome and public pages; the studio shell with account, personal and site
administration separated; the content editor; then dark theme and mobile polish,
removing styles no component uses. Keep this proposal pending until the last step
moves it to implemented and records what shipped.

Each step runs `./gradlew frontendCheck`, keeps the frontend contract tests
passing, and is exercised in the isolated browser entrance at desktop and phone
widths for the pages it changes. Screenshot review covers anonymous and signed-in
states. Any backend change needed by a page ships in the same step with its tests.
