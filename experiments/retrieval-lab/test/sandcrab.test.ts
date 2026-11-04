import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { test } from "node:test";
import { setTimeout as delay } from "node:timers/promises";
import { SandcrabError } from "../src/sandcrab.js";
import { Worker } from "../src/worker.js";
import type { Json } from "../src/types.js";
import { sandboxFixture } from "./sandcrab-fixture.js";

test("ordinary volumes, streamed bundle bytes and consumer Git initialization replace signed operations", async () => {
  const f = await sandboxFixture();
  try {
    const session = await f.worker.open(new AbortController().signal);
    try {
      const init = f.calls.find((c) => c.path.endsWith("/commands"))!;
      assert.match(
        (init.body as Json).command,
        /git .*fetch --no-tags \.\/corpus.bundle HEAD/,
      );
      assert.equal((init.body as Json).timeoutMillis, 120_000);
      assert.equal(
        f.calls.some(
          (c) => c.path === "/v1/requests" || c.path === "/v1/hello",
        ),
        false,
      );
      const large = await session.execute("large-output");
      assert.equal(large.stdout.length, 16_384);
      assert.equal(large.stdoutTruncated, true);
      const page = await session.artifact(
        large.artifacts.stdout.artifactId,
        16_384,
      );
      assert.equal(
        Buffer.from(page.data, "base64").toString(),
        "x".repeat(7616) + "\n",
      );
      assert.equal(page.eof, true);
      await assert.rejects(
        session.artifact(`${randomUUID()}:stdout`, 0),
        /output reference/,
      );
      await assert.rejects(
        session.artifact(large.artifacts.stdout.artifactId, 24_002),
        /offset/,
      );
      await assert.rejects(
        f.worker.open(new AbortController().signal),
        /active/,
      );
    } finally {
      await session.close();
    }
    assert.deepEqual(f.store.metadata("sandboxCopies"), []);
    for (const records of Object.values(f.records))
      assert.equal(records.size, 0);
    const close = f.calls.findIndex((c) => c.path.endsWith("/close"));
    const deletion = f.calls.findIndex((c) => c.path.endsWith("/delete"));
    assert.ok(close > 0 && deletion > close);
  } finally {
    await f.close();
  }
});

test("a lost command reply never reexecutes the command and closes its owned session", async () => {
  const f = await sandboxFixture();
  try {
    const session = await f.worker.open(new AbortController().signal);
    f.state.after = (_method, path) =>
      path.endsWith("/commands") ? "drop" : undefined;
    await assert.rejects(session.execute("once"));
    await session.close();
    assert.equal(
      f.calls.filter((c) => (c.body as Json).command === "once").length,
      1,
    );
    assert.deepEqual(f.store.metadata("sandboxCopies"), []);
  } finally {
    await f.close();
  }
});

test("a lost admitted volume reply is reconciled with its original identity", async () => {
  const f = await sandboxFixture();
  try {
    f.state.after = (method, path) =>
      method === "POST" && path === "/v1/volumes" ? "drop" : undefined;
    await assert.rejects(f.worker.open(new AbortController().signal));
    assert.equal(
      f.calls.filter((c) => c.method === "POST" && c.path === "/v1/volumes")
        .length,
      1,
    );
    assert.equal(f.records.volumes.size, 0);
    assert.deepEqual(f.store.metadata("sandboxCopies"), []);
  } finally {
    await f.close();
  }
});

test("an uncertain durable volume keeps its receipt across daemon epochs", async () => {
  const f = await sandboxFixture();
  try {
    f.state.before = (method, path) =>
      method === "POST" && path === "/v1/volumes" ? "drop" : undefined;
    await assert.rejects(
      f.worker.open(new AbortController().signal),
      AggregateError,
    );
    const retained = f.store.metadata<Json[]>("sandboxCopies")!;
    assert.equal(retained[0]!.volume, "pending");
    await assert.rejects(
      f.worker.open(new AbortController().signal),
      /disposal is uncertain/,
    );
    assert.equal(
      f.calls.filter((c) => c.method === "POST" && c.path === "/v1/volumes")
        .length,
      1,
    );
    assert.deepEqual(f.store.metadata("sandboxCopies"), retained);
    f.info.serverId = randomUUID();
    await assert.rejects(f.worker.cleanup(), /inspect the original store/);
    assert.deepEqual(f.store.metadata("sandboxCopies"), retained);
  } finally {
    await f.close();
  }
});

test("failed cleanup preserves identity and a replacement consumer finishes it", async () => {
  const f = await sandboxFixture();
  try {
    const session = await f.worker.open(new AbortController().signal);
    f.state.before = (_method, path) => {
      if (path.endsWith("/delete"))
        throw new SandcrabError(409, "STORAGE_UNCERTAIN");
    };
    await assert.rejects(session.close(), /STORAGE_UNCERTAIN/);
    const retained = f.store.metadata<Json[]>("sandboxCopies")!;
    assert.equal(retained.length, 1);
    assert.equal(f.records.volumes.size, 1);
    await assert.rejects(
      f.worker.open(new AbortController().signal),
      /STORAGE_UNCERTAIN/,
    );
    f.state.before = undefined;
    const replacement = new Worker(f.settings, f.store, f.manifest, f.api);
    await replacement.cleanup();
    assert.deepEqual(f.store.metadata("sandboxCopies"), []);
    assert.equal(
      f.calls.filter((c) => c.method === "POST" && c.path === "/v1/volumes")
        .length,
      1,
    );
  } finally {
    await f.close();
  }
});

test("cancellation contains an active command before volume disposal", async () => {
  const f = await sandboxFixture();
  try {
    const abort = new AbortController();
    const session = await f.worker.open(abort.signal);
    const command = session.execute("pending");
    const rejected = assert.rejects(command);
    for (
      let i = 0;
      i < 100 && !f.calls.some((c) => (c.body as Json).command === "pending");
      i++
    )
      await delay(5);
    assert.ok(f.calls.some((c) => (c.body as Json).command === "pending"));
    abort.abort(new Error("operator cancellation"));
    await rejected;
    await session.close();
    assert.deepEqual(f.store.metadata("sandboxCopies"), []);
    for (const records of Object.values(f.records))
      assert.equal(records.size, 0);
  } finally {
    await f.close();
  }
});

test("timeouts end the native shell without silently creating a replacement", async () => {
  const f = await sandboxFixture();
  try {
    const session = await f.worker.open(new AbortController().signal);
    try {
      assert.equal((await session.execute("timeout")).timedOut, true);
      await assert.rejects(session.execute("after-timeout"), /session ended/);
      assert.equal(
        f.calls.some((c) => (c.body as Json).command === "after-timeout"),
        false,
      );
      assert.equal(
        f.calls.filter((c) => c.method === "POST" && c.path === "/v1/sessions")
          .length,
        1,
      );
    } finally {
      await session.close();
    }
  } finally {
    await f.close();
  }
});

test("wrong command epochs are rejected even when the result claims success", async () => {
  const f = await sandboxFixture();
  try {
    const session = await f.worker.open(new AbortController().signal);
    f.state.after = (_method, path, value) => {
      if (path.endsWith("/commands")) value.serverId = randomUUID();
    };
    await assert.rejects(session.execute("wrong-epoch"), /changed identity/);
    await assert.rejects(session.close(), /changed identity/);
    assert.equal(f.store.metadata<Json[]>("sandboxCopies")!.length, 1);
  } finally {
    await f.close();
  }
});

test("an undersized or unsafe profile never allocates a reduced corpus", async () => {
  const f = await sandboxFixture();
  try {
    f.info.storage.quotaEnforced = false;
    await assert.rejects(
      f.worker.open(new AbortController().signal),
      /requires enforced quotas/,
    );
    assert.equal(
      f.calls.some((c) => c.method === "POST"),
      false,
    );
    f.info.storage.quotaEnforced = true;
    f.info.profiles.corpus.transferBytes = 1024;
    await assert.rejects(
      f.worker.open(new AbortController().signal),
      /experiment bounds/,
    );
    assert.equal(
      f.calls.some((c) => c.method === "POST"),
      false,
    );
    f.info.profiles.corpus.transferBytes = 268_435_456;
    f.info.profiles.corpus.hostCalls = true;
    await assert.rejects(
      f.worker.open(new AbortController().signal),
      /experiment bounds/,
    );
    assert.equal(
      f.calls.some((c) => c.method === "POST"),
      false,
    );
  } finally {
    await f.close();
  }
});

test("legacy cleanup receipts remain intact and block native admission", async () => {
  const f = await sandboxFixture();
  try {
    const legacy = [{ copyId: randomUUID(), exportId: randomUUID() }];
    f.store.setMetadata("workerCopies", legacy);
    await assert.rejects(
      f.worker.open(new AbortController().signal),
      /operator cleanup/,
    );
    assert.deepEqual(f.store.metadata("workerCopies"), legacy);
    assert.equal(f.calls.length, 0);
  } finally {
    await f.close();
  }
});

test("a definite storage-capacity refusal does not become an unknown allocation", async () => {
  const f = await sandboxFixture();
  try {
    f.state.before = (method, path) => {
      if (method === "POST" && path === "/v1/volumes")
        throw new SandcrabError(429, "STORAGE_CAPACITY");
    };
    await assert.rejects(
      f.worker.open(new AbortController().signal),
      /STORAGE_CAPACITY/,
    );
    assert.deepEqual(f.store.metadata("sandboxCopies"), []);
    assert.equal(
      f.calls.filter((c) => c.method === "POST" && c.path === "/v1/volumes")
        .length,
      1,
    );
    f.state.before = undefined;
    const session = await f.worker.open(new AbortController().signal);
    await session.close();
  } finally {
    await f.close();
  }
});

test("cleanup cannot reinterpret another service's missing IDs as proof of disposal", async () => {
  const f = await sandboxFixture();
  try {
    const session = await f.worker.open(new AbortController().signal);
    try {
      const retained = f.store.metadata("sandboxCopies");
      const other = new Worker(
        { ...f.settings, workerSocket: f.settings.workerSocket + "-other" },
        f.store,
        f.manifest,
        f.api,
      );
      const before = f.calls.length;
      await assert.rejects(other.cleanup(), /another socket/);
      assert.equal(f.calls.length, before);
      assert.deepEqual(f.store.metadata("sandboxCopies"), retained);
    } finally {
      await session.close();
    }
  } finally {
    await f.close();
  }
});

test("an uncertain streamed write is never repeated and the owned volume is disposed", async () => {
  const f = await sandboxFixture();
  try {
    f.state.after = (method) => (method === "PUT" ? "drop" : undefined);
    await assert.rejects(f.worker.open(new AbortController().signal));
    assert.equal(f.calls.filter((c) => c.method === "PUT").length, 1);
    assert.equal(
      f.calls.some((c) => c.path.endsWith("/commands")),
      false,
    );
    assert.equal(f.records.volumes.size, 0);
    assert.deepEqual(f.store.metadata("sandboxCopies"), []);
  } finally {
    await f.close();
  }
});

test("mismatched upload bytes cannot reach corpus initialization", async () => {
  const f = await sandboxFixture();
  try {
    f.state.after = (method, _path, value) => {
      if (method === "PUT") value.sha256 = "0".repeat(64);
    };
    await assert.rejects(
      f.worker.open(new AbortController().signal),
      /differs from the prepared manifest/,
    );
    assert.equal(
      f.calls.some((c) => c.path.endsWith("/commands")),
      false,
    );
    assert.equal(f.records.volumes.size, 0);
  } finally {
    await f.close();
  }
});

test("a lost forget acknowledgement releases only a previously confirmed deleted volume", async () => {
  const f = await sandboxFixture();
  try {
    const session = await f.worker.open(new AbortController().signal);
    f.state.after = (_method, path) =>
      path.startsWith("/v1/volumes/") && path.endsWith("/forget")
        ? "drop"
        : undefined;
    await assert.rejects(session.close());
    assert.equal(
      f.store.metadata<Json[]>("sandboxCopies")![0]!.volume,
      "deleted",
    );
    assert.equal(f.records.volumes.size, 0);
    await f.worker.cleanup();
    assert.deepEqual(f.store.metadata("sandboxCopies"), []);
  } finally {
    await f.close();
  }
});
