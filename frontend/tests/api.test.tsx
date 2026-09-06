import assert from "node:assert/strict";
import test from "node:test";
import { api, ApiError } from "../lib/browser-api";
import { allArticles, articles, PublicApiError } from "../lib/public-api";

test("browser writes get fresh CSRF tokens and preserve upload idempotency headers", async () => {
  const previous = globalThis.fetch;
  const calls: { path: string; options?: RequestInit }[] = [];
  let token = 0;
  globalThis.fetch = async (input, options) => {
    calls.push({ path: String(input), options });
    if (String(input).endsWith("/csrf"))
      return Response.json({
        headerName: "X-CSRF-TOKEN",
        token: "token-" + ++token,
      });
    return new Response(null, { status: 204 });
  };
  try {
    await api("/api/auth/login", {
      method: "POST",
      form: new URLSearchParams({
        username: "test",
        password: "test-password",
      }),
    });
    await api("/api/admin/assets", {
      method: "POST",
      multipart: new FormData(),
      headers: { "Idempotency-Key": "operation-a" },
    });
    assert.equal(calls.length, 4);
    assert.equal(
      (calls[1].options?.headers as Record<string, string>)["X-CSRF-TOKEN"],
      "token-1",
    );
    assert.equal(
      (calls[3].options?.headers as Record<string, string>)["X-CSRF-TOKEN"],
      "token-2",
    );
    assert.equal(
      (calls[3].options?.headers as Record<string, string>)["Idempotency-Key"],
      "operation-a",
    );
    assert.equal(
      (calls[3].options?.headers as Record<string, string>)["Content-Type"],
      undefined,
    );
    assert.equal(calls[3].options?.credentials, "same-origin");
  } finally {
    globalThis.fetch = previous;
  }
});

test("uncertain browser mutation does not retry or claim success", async () => {
  const previous = globalThis.fetch;
  let writes = 0;
  globalThis.fetch = async (input) => {
    if (String(input).endsWith("/csrf"))
      return Response.json({ headerName: "X-CSRF", token: "token" });
    writes++;
    throw new TypeError("network lost");
  };
  try {
    await assert.rejects(
      api("/api/admin/repository/patch", { method: "POST", body: {} }),
      (error) =>
        error instanceof ApiError &&
        error.status === 0 &&
        error.message.includes("无法确认"),
    );
    assert.equal(writes, 1);
  } finally {
    globalThis.fetch = previous;
  }
});

test("server public reads are uncached and never forward a browser identity", async () => {
  const previous = globalThis.fetch;
  globalThis.fetch = async (_input, options) => {
    assert.equal(options?.cache, "no-store");
    assert.equal(
      (options?.headers as Record<string, string>).Cookie,
      undefined,
    );
    return Response.json({ items: [], total: 0 });
  };
  try {
    assert.equal((await articles()).total, 0);
  } finally {
    globalThis.fetch = previous;
  }
});

test("sitemap enumeration fails instead of mixing publication commits", async () => {
  const previous = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async () =>
    Response.json({
      commit: ++calls === 1 ? "before" : "after",
      items: [],
      total: 101,
      offset: 0,
      limit: 100,
    });
  try {
    await assert.rejects(
      allArticles(),
      (error) => error instanceof PublicApiError && error.status === 503,
    );
  } finally {
    globalThis.fetch = previous;
  }
});
