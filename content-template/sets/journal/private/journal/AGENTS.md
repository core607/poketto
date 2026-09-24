# Journal drafts

Weekly and daily journal entries, written here first.

- One file per period: `YYYY/YYYY-MM-DD.md` for a day, `YYYY/YYYY-Www.md` for an
  ISO week (for example `2026/2026-W39.md`). Continue an existing entry for the
  same period instead of starting a second one.
- Frontmatter: `title`, `created_at` (the period's first day), `tags`, and a fresh
  lowercase UUID `id` when the entry may be published.
- Suggested sections: what happened, what was learned, what comes next. Keep the
  owner's voice; do not invent events.
- To publish, move the finished entry to the same relative path under
  `public/journal/`. Add `publish_at` to release it later.
