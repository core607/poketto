# News digest drafts

Short write-ups of news and papers the owner follows, one file per story:
`YYYY-MM-DD-<slug>.md`, dated by the event or publication.

- Frontmatter: `title`, `created_at`, `tags` (subject first, for example `AI`),
  and a fresh lowercase UUID `id`.
- Structure: a bold **TL;DR** sentence, then background, the key points, and a
  closing list of sources with links. Every claim needs a source; say when a
  report is unconfirmed.
- Summarize in the owner's words. Quote at most a sentence from any source, and
  never copy an article's text.
- A cover image goes first, with its caption on the next line in italics, and a
  caption says when an image is illustrative or generated.
- To publish, move the story to `public/digest/` with the same name, or add
  `publish_at` there to release it later.
