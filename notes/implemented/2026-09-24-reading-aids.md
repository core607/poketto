# Reading aids

Date: 2026-09-24

## Problem

Long articles gave readers no outline and no sense of length. Fenced code rendered as uniform text even when the author named its language. The public page and the editor preview share one restricted renderer, [components/markdown.tsx](../../frontend/components/markdown.tsx), described by the [blog and browser interface](2026-09-06-blog-browser-interface.md). Any aid added there must keep raw HTML discarded and add no client script to public pages.

## Decision

**Table of contents.** [lib/reading.ts](../../frontend/lib/reading.ts) parses the body with the same remark, GFM, remark-rehype and heading-slug steps as the renderer, so every listed anchor is the anchor on the page. It lists headings from h1 to h3 at the two shallowest levels in use. It skips an opening heading that repeats the title, as [reading-heading](../../frontend/lib/reading-heading.ts) hides it, and skips the generated footnote label. The reading page shows the list only for three or more headings: in the sticky rail beside the text on wide screens, and as a collapsible **目录** above the text below 1080 px. The rail keeps any collection panel below the contents.

**Reading time.** The page counts CJK characters at about 400 a minute and other words at about 200 a minute, rounded, never below one minute. Folder pages show no estimate.

**Code highlighting.** rehype-highlight runs server-side with language detection off. A fenced block is highlighted only when its info string names a language in the highlight.js common set; an unnamed or unknown language stays plain text. Token colours are theme variables in [base.css](../../frontend/app/styles/base.css) with light and dark values.

## Alternatives

- **Detecting the language of unnamed blocks.** Detection guesses wrong on short snippets and prose, and costs a scoring pass per block. The author's info string is the only signal used.
- **Collecting headings inside the renderer and passing them up.** The reading page renders the Markdown as a child, so a plugin's side output would arrive after the page chose its layout. The page parses a second time instead; the cost is one extra parse of a body already bounded at 1 MiB.
- **A client-side outline that reads rendered headings.** It would ship script to every article and would miss headings in server output until hydration.

## Consequences

- The editor bundle includes the highlighter, because the preview is a client component that uses the same renderer.
- A heading level that jumps from h2 to h4 lists only the h2 headings, since h4 is outside the listed range.
- Readers still see anchors on every heading. The contents list only helps navigation and does not change rendered Markdown.

## Verification

[tests/reading-guide.test.tsx](../../frontend/tests/reading-guide.test.tsx) covers:
- contents that skip a title heading, drop h4, keep duplicate headings distinct and match the rendered anchors;
- the reading-time counts;
- highlighting of named, unnamed and unknown languages;
- a rendered article that shows contents and time when long, and only time when short.
