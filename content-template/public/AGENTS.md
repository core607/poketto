# Public content

Place only content the owner intends to publish here. Publication also requires
the repository policy to be enabled; exclusions win. The exact root `public/`
defines the boundary. A category named `private` inside this tree is still public.
Guides and hidden paths are never published.

Default article routes omit `public/` and the `.md` extension. `index.md` owns its
folder route; `public/index.md` owns `/`. Preserve an existing explicit route when
reorganizing an article. Required media must also be eligible for publication.

To publish an article later, add frontmatter `publish_at` with an offset timestamp,
for example `publish_at: 2026-10-01T09:00:00+08:00`. The article stays off the
website until then and appears without another commit; a date alone means 00:00 UTC.
An invalid value keeps the article unpublished and reports a diagnostic.

Public articles with unique UUID frontmatter IDs can receive community interactions.
Preserve the ID when moving the same article. Duplicate IDs keep articles readable
but disable their interaction entrances until the identity conflict is resolved.

When withdrawing content, check remaining public references before moving it to
`private/`. A private reference cannot make bytes secret while another public
reference still exposes them. Public ZIP export packages already-public content;
it does not publish private selections.
