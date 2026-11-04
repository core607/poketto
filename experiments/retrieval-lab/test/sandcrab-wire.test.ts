import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { createServer } from "node:net";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { test } from "node:test";
import { exchange, SandcrabError } from "../src/sandcrab.js";

async function wire(
  response: string | undefined,
  check: (socket: string, calls: () => number) => Promise<void>,
) {
  const endpoint =
    process.platform === "win32"
      ? `\\\\.\\pipe\\lab-wire-${randomUUID()}`
      : join(tmpdir(), `lab-${randomUUID()}.sock`);
  let calls = 0;
  const peers = new Set<import("node:net").Socket>();
  const server = createServer((peer) => {
    peers.add(peer);
    peer.on("error", () => {});
    peer.on("close", () => peers.delete(peer));
    peer.once("data", () => {
      calls++;
      if (response !== undefined) peer.end(response);
    });
  });
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(endpoint, resolve);
  });
  try {
    await check(endpoint, () => calls);
  } finally {
    for (const peer of peers) peer.destroy();
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
}

test("native framing rejects oversized, truncated, chunked and malformed replies without replay", async () => {
  for (const response of [
    "HTTP/1.1 200 OK\r\nContent-Length: 8388609\r\n\r\n",
    "HTTP/1.1 200 OK\r\nContent-Length: 20\r\n\r\n{}",
    "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\n{}\r\n0\r\n\r\n",
    "HTTP/1.1 200 OK\r\nContent-Length: 14\r\n\r\nprivate-secret",
  ])
    await wire(response, async (socket, calls) => {
      await assert.rejects(
        exchange(socket, "POST", "/v1/sessions", Buffer.from("{}"), 2),
        (error: Error) => !error.message.includes("private-secret"),
      );
      assert.equal(calls(), 1);
    });
});

test("native refusal preserves its typed code without quoting peer data", async () => {
  const body = JSON.stringify({
    error: { code: "SESSION_NOT_READY", private: "private-secret" },
  });
  await wire(
    `HTTP/1.1 409 Conflict\r\nContent-Length: ${Buffer.byteLength(body)}\r\n\r\n${body}`,
    async (socket) => {
      await assert.rejects(
        exchange(
          socket,
          "POST",
          "/v1/sessions/x/commands",
          Buffer.from("{}"),
          2,
        ),
        (error: Error) =>
          error instanceof SandcrabError &&
          error.status === 409 &&
          error.code === "SESSION_NOT_READY" &&
          !error.message.includes("private-secret"),
      );
    },
  );
});

test("an unresponsive peer is bounded by the whole request deadline", async () => {
  await wire(undefined, async (socket, calls) => {
    await assert.rejects(
      exchange(socket, "GET", "/v1/info", undefined, 0, undefined, 50),
      /aborted/,
    );
    assert.equal(calls(), 1);
  });
});
