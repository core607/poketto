# Public content

Place only content the owner intends to publish here. Publication also requires
the repository policy to be enabled; exclusions win. The exact root `public/`
defines the boundary. A category named `private` inside this tree is still public.
Guides and hidden paths are never published.

Default article routes omit `public/` and the `.md` extension. `index.md` owns its
folder route; `public/index.md` owns `/`. Preserve an existing explicit route when
reorganizing an article. Required media must also be eligible for publication.

When withdrawing content, check remaining public references before moving it to
`private/`. A private reference cannot make bytes secret while another public
reference still exposes them. Public ZIP export packages already-public content;
it does not publish private selections.
