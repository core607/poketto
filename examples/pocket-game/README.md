# Pocket lantern game template

Copy this directory beneath a content repository's public root and assign a new
UUID to both the introduction's `id` and the manifest's `articleId`. Keep the
manifest beside its introduction as `<article-filename>.game.json`. Every declared
file must be public under that commit's publishing policy; references cannot leave
the package directory or select hidden paths. The author-provided `小游戏` tag is
reserved and ignored. Only current successful platform validation supplies the
game marker and entrance.

`rules.mjs` exports synchronous `init({seed, resources})`, `observe(state, context)`
and `act(state, action, context)` functions. `context.resources` maps declared local
resource names to `{mediaType, data}`, with Base64 data. Bundle dependencies into
the rule module; runtime downloads are unavailable. Put randomness and all progress
in the returned JSON state. Time, process memory, network and platform APIs are not
part of the rule contract.

Observations contain `text`, an `actions` list of unique `{id, label}` values and
optional `done`. A completed observation has no actions. The runner accepts only an
action offered by the current observation and returns the resulting state and
observation together. State is bounded to 32 KiB of UTF-8 JSON, 16 nesting levels
and 2048 entries per object or array. There may be at most 32 offered actions.

The optional presentation module exports synchronous `present(state, observation,
context)`. It returns `{heading, paragraphs, image?}`; `image` names a declared PNG,
JPEG or WebP resource. The platform renders text and action buttons. The component
does not receive DOM, HTML insertion, account credentials or a platform save API.
Omitting it uses the ordinary text observation.

The manifest allows 32 resources. Rule source is at most 256 KiB, presentation
source 64 KiB, manifest 16 KiB, each resource's Base64 data 256 KiB (up to 192 KiB
before encoding), and the complete encoded package 384 KiB. Version identity
follows the manifest and declared file bytes, so an
unrelated repository edit does not change the game version. The platform still
rechecks publication at the current snapshot before delivery.

Check the example with the frontend's `game-runtime.test.tsx`. The same runtime is
copied verbatim by `stageGameWorker`; that test exercises the rule contract, not
Linux containment or browser isolation.
