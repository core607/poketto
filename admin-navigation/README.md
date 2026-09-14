# Admin content navigation acceptance evidence

- Source: `479fb87e3b7689897a370efa3e9db72472513949`
- Browser: Chrome extension provider `1`; agent-created admin tab was preserved with handoff.
- Stack: disposable local Spring acceptance runtime, PostgreSQL 17.11, production Next build, and Caddy from `acceptance/compose.yaml`; bound to `127.0.0.1:38180`.
- Fixture: `POKETTO_ACCEPTANCE_MULTIPLE_WORKSPACES=true`, two local spaces (`Default workspace` and `阅读室`), seeded private/public folders and files. The disposable compose volume is removed after this evidence is written.
- Credentials: disposable local acceptance credentials were used and are absent from this artifact.

## Local reproduction

From the repository root, prepare an ignored `acceptance/.env` with a generated disposable `POKETTO_ACCEPTANCE_PASSWORD`, `POKETTO_ACCEPTANCE_REVISION=479fb87e3b7689897a370efa3e9db72472513949`, `POKETTO_ACCEPTANCE_ORIGIN=http://127.0.0.1:38180`, `POKETTO_ACCEPTANCE_PORT=38180`, and `POKETTO_ACCEPTANCE_MULTIPLE_WORKSPACES=true`. Then run:

```text
.\gradlew.bat --no-daemon stageAcceptanceRuntime
docker compose --env-file acceptance/.env -f acceptance/compose.yaml up --build -d
curl.exe --fail http://127.0.0.1:38180/
docker compose --env-file acceptance/.env -f acceptance/compose.yaml down --volumes --remove-orphans
```

The gateway is bound to loopback only. This procedure is a disposable local fixture; it does not exercise production HTTPS, external MCP clients, or real remote repositories. Remove or blank `acceptance/.env` after the run.

## Verified flows

- Opened `private/日记.md` while the selected folder was `private/相册`; the URL retained independent `folder` and `path` values.
- With a dirty editor, Back opened the discard prompt. Cancel returned to the same folder/path and retained the draft. Confirmed Back changed to the prior entry. A dirty Forward prompt was cancelled without consuming the forward entry; a second Forward plus confirmation reached the target entry.
- Switched from `Default workspace` to `阅读室` and back; the workspace query changed and the second space rendered its own content tree.
- From `public/drafts`, opened new-note creation. The focused name input was visible and the form stated that the note defaults to `private/drafts/`. Preparing `浏览器私有草稿` opened `private/drafts/浏览器私有草稿.md` without adding it to the tree; explicit Save added it and showed `已保存`.
- Casefold/stale-state conflict: tab A prepared `private/RaceNote.md`; tab B independently prepared and saved `private/racenote.md`; tab A then saved its retained draft and showed `操作与当前状态冲突，请重新读取后核对。` while keeping `RACENOTE_CONFLICT_DRAFT` in the editor.
- With the browser viewport requested at `390x844`, the focused new-note name input rendered on the mobile layout. The live page reported CSS `window.innerWidth=390` and `window.innerHeight=844`; `document.documentElement.scrollWidth=375` and `document.body.scrollWidth=375`, so no horizontal overflow was observed. The raw screenshot API capture is independently `375x812` (the dimensions recorded below).

## Raw screenshots

The JPEG files in this directory are the bytes returned by the browser screenshot API and were not edited or re-encoded.

| File | Bytes | SHA-256 | Raw capture dimensions |
| --- | ---: | --- | --- |
| `desktop-conflict.jpg` | 56,835 | `7d29ed69bd66fcfb868d9900e532ebe13ea6f78db0a40252de659fce9d907985` | 1905x882 |
| `desktop-dirty-prompt.jpg` | 58,653 | `a66bee26acafd99e8e1dde0e091df4239645a097e03f3535216dbf03de24289a` | 1905x882 |
| `mobile-navigation.jpg` | 23,921 | `70dae74b33f8ef347ecef4171890ece70dce14d506e2ee28e38131f325af79e6` | 375x812 |

## Checks and scope

`frontendCheck` passed all 79 frontend tests, including type generation, TypeScript, formatting, and the production build. `stageAcceptanceRuntime` and `repoCheck` passed on the same source. This is a local synthetic acceptance run; production HTTPS, external MCP clients, and real remote repositories were not claimed.
