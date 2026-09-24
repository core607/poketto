# Reading aids

Date: 2026-09-24

## Problem

Long articles gave readers no outline and no sense of length. Fenced code rendered as uniform text even when the author named its language, and formulas and diagrams could not be written at all. The public page and the editor preview share one restricted renderer, [components/markdown.tsx](../../frontend/components/markdown.tsx), described by the [blog and browser interface](2026-09-06-blog-browser-interface.md). Any aid added there must keep raw HTML discarded, render on the server where it can, and load client script only on pages that need the browser.

## Decision

**Table of contents.** [lib/reading.ts](../../frontend/lib/reading.ts) parses the body with the syntax and heading-anchor steps that [reading-heading](../../frontend/lib/reading-heading.ts) exports for both the renderer and itself, so every listed anchor is the anchor on the page. The same module owns the title-repeat rule and the anchor link spelling. The guide lists headings from h1 to h3 at the two shallowest levels in use. It skips an opening heading that repeats the title, which the page hides, and skips the generated footnote label. The reading page shows the list only for three or more headings: in the sticky rail beside the text on wide screens, and as a collapsible **目录** above the text below 1080 px. The rail keeps any collection panel below the contents.

**Reading time.** The page counts CJK characters at about 400 a minute and other words at about 200 a minute, rounded, never below one minute. Folder pages show no estimate.

**Math.** remark-math is part of the shared syntax, so the reading guide and the renderer see the same headings.
- It recognises only doubled dollars: `$$…$$` inline, `$$` fences for display math, and ```` ```math ```` blocks.
- A single dollar stays text, so prices such as "$100 and $250" keep reading as prose.
- rehype-katex renders on the server with `trust: false`, which disables commands such as `\href` and `\url`.
- Invalid input renders as a marked error instead of failing the page.
- The stylesheet and fonts come from `katex` 0.16.47, pinned to the version rehype-katex renders with, so markup and styles always match. A test compares the two versions. The page CSP allows same-origin fonts.

**Diagrams.** A ```` ```mermaid ```` block renders on the server as its source code.
- In the browser, [MermaidDiagram](../../frontend/components/mermaid-diagram.tsx) loads Mermaid only on pages that contain a diagram. It draws with `securityLevel: "strict"`, which escapes labels and drops click handlers.
- The inserted SVG cannot run script, because the page CSP refuses inline script and event handlers.
- The diagram follows the light or dark theme and redraws when the theme changes.
- If Mermaid fails, the source stays visible.

**Code highlighting.** rehype-highlight runs server-side with language detection off. A fenced block is highlighted only when its info string names a language in the highlight.js common set; an unnamed or unknown language stays plain text. Token colours are theme variables in [base.css](../../frontend/app/styles/base.css) with light and dark values.

## Alternatives

- **Detecting the language of unnamed blocks.** Detection guesses wrong on short snippets and prose, and costs a scoring pass per block. The author's info string is the only signal used.
- **Collecting headings inside the renderer and passing them up.** The reading page renders the Markdown as a child, so a plugin's side output would arrive after the page chose its layout. The page parses a second time instead; the cost is one extra parse of a body already bounded at 1 MiB.
- **Single-dollar inline math.** It is the common convention, but existing articles write prices with `$`, and a stray pair would turn prose into a formula.
- **Rendering Mermaid on the server.** Mermaid measures text with browser layout, so server rendering would need a headless browser per page render.
- **Mermaid 12.** Its parser dependencies required a `lodash-es` range with known high-severity advisories when this was adopted. Mermaid 11.17.2, with `lodash-es` resolved to 4.18.1, audits clean.
- **A client-side outline that reads rendered headings.** It would ship script to every article and would miss headings in server output until hydration.

## Consequences

- The editor bundle includes the highlighter and KaTeX, because the preview is a client component that uses the same renderer. Mermaid stays in a separately loaded chunk.
- A diagram appears only after script runs. Readers without script, crawlers and feeds see its source.
- The contents and reading time see math as its TeX source. A heading containing `$$x^2$$` is listed as `x^2`, and TeX commands count as words.
- Each diagram draw uses a fresh element id, so a redraw after a theme change never collides with the draw it replaces.
- A heading level that jumps from h2 to h4 lists only the h2 headings, since h4 is outside the listed range.
- Readers still see anchors on every heading. The contents list only helps navigation and does not change rendered Markdown.

## Verification

[tests/reading-guide.test.tsx](../../frontend/tests/reading-guide.test.tsx) covers:
- contents that skip a title heading, drop h4, keep duplicate headings distinct and match the rendered anchors;
- the reading-time counts;
- highlighting of named, unnamed and unknown languages;
- a rendered article that shows contents and time when long, and only time when short.

[tests/math-diagrams.test.tsx](../../frontend/tests/math-diagrams.test.tsx) covers:
- inline, display and fenced math, and prices left as text;
- invalid input and a disabled `\href`;
- the server-rendered Mermaid source;
- contents anchors for a heading that contains math.

A standalone build under the production CSP was checked in Chrome in both themes. The KaTeX fonts loaded, both formulas rendered, and the Mermaid SVG drew with no console errors from the page.
