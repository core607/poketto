import assert from "node:assert/strict";
import test from "node:test";
import { renderToStaticMarkup } from "react-dom/server";
import { EditorPublicPage } from "../components/editor-public-page";
import type { RepositoryFile } from "../lib/types";

test("a scheduled file names its release time instead of a public link", () => {
  const file: RepositoryFile = {
    publicScope: true,
    publicPage: {
      state: "SCHEDULED",
      space: "home",
      route: null,
      publishAt: "2026-10-01T01:00:00Z",
    },
    commit: "a".repeat(40),
    path: "public/later.md",
    source: "# Later",
    revision: "sha256:0",
    expectedAbsence: false,
    diagnostics: [],
  };
  const html = renderToStaticMarkup(
    <EditorPublicPage
      file={file}
      path="public/later.md"
      dirty={false}
      pending={false}
      onRefresh={() => {}}
    />,
  );
  assert.match(html, /定时发布：/);
  assert.match(html, /2026/);
  assert.doesNotMatch(html, /查看公开页面/);
});
