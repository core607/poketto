import assert from "node:assert/strict";
import { createHash, randomUUID } from "node:crypto";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { config } from "../src/config.js";
import { Sandcrab, SandcrabError } from "../src/sandcrab.js";
import { Store } from "../src/store.js";
import { Worker, type CorpusManifest } from "../src/worker.js";
import type { Json } from "../src/types.js";

/** Synthetic protocol peer over a real local HTTP connection; it never executes guest code. */
export async function sandboxFixture() {
  const root = mkdtempSync(join(tmpdir(), "lab-native-"));
  const socket =
    process.platform === "win32"
      ? `\\\\.\\pipe\\lab-native-${randomUUID()}`
      : join(root, "http.sock");
  const store = new Store(join(root, "runs.db"));
  const bundle = Buffer.from("synthetic complete bundle bytes");
  writeFileSync(join(root, "corpus.bundle"), bundle);
  const manifest: CorpusManifest = {
    commit: "a".repeat(40),
    bundleSha256: createHash("sha256").update(bundle).digest("hex"),
    bundleBytes: bundle.length,
    documents: 2,
    files: 3,
    preparationSeconds: 1,
    textBytes: 100,
  };
  const settings = {
    ...config(),
    data: root,
    workerSocket: socket,
    workerProfile: "corpus",
  };
  const state = {
    info: {
      apiVersion: 1,
      serverId: randomUUID(),
      capabilities: ["runs", "sessions", "volumes", "files"],
      profiles: {
        corpus: {
          network: "none",
          hostCalls: false,
          timeoutMillis: 120_000,
          outputLimitBytes: 1_048_576,
          transferBytes: 268_435_456,
          sessionTtlMillis: 60_000,
        },
      },
      storage: {
        available: true,
        quotaEnforced: true,
        maxVolumeBytes: 2_147_483_648,
        maxVolumeInodes: 150_000,
      },
    },
    records: {
      volumes: new Map<string, Json>(),
      sessions: new Map<string, Json>(),
      runs: new Map<string, Json>(),
    },
    calls: [] as { method: string; path: string; body: Json | Buffer }[],
    before: undefined as
      | undefined
      | ((method: string, path: string, body: Json | Buffer) => "drop" | void),
    after: undefined as
      | undefined
      | ((method: string, path: string, value: Json) => "drop" | void),
  };
  const server = createServer(async (req, res) => {
    const chunks: Buffer[] = [];
    try {
      for await (const part of req) chunks.push(part);
      const raw = Buffer.concat(chunks);
      const method = req.method!,
        path = req.url!;
      const body: Json | Buffer =
        method === "PUT" ? raw : raw.length ? JSON.parse(raw.toString()) : {};
      state.calls.push({ method, path, body });
      if (state.before?.(method, path, body) === "drop") {
        req.socket.destroy();
        return;
      }
      const value = handle(method, path, body);
      if (state.after?.(method, path, value) === "drop") {
        req.socket.destroy();
        return;
      }
      const bytes = Buffer.from(JSON.stringify(value));
      res.writeHead(200, {
        "Content-Length": bytes.length,
        "Content-Type": "application/json",
      });
      res.end(bytes);
    } catch (error) {
      const known = error instanceof SandcrabError;
      const bytes = Buffer.from(
        JSON.stringify({
          error: { code: known ? error.code : "FIXTURE_FAILURE" },
        }),
      );
      res.writeHead(known ? error.status : 500, {
        "Content-Length": bytes.length,
      });
      res.end(bytes);
    }
  });
  function handle(method: string, path: string, body: Json | Buffer): Json {
    if (path === "/v1/info") return state.info;
    const [, version, resource, id, action] = path.split("/");
    assert.equal(version, "v1");
    assert.ok(resource && resource in state.records);
    const kind = resource as keyof typeof state.records;
    const records = state.records[kind];
    if (!id && method === "POST" && !Buffer.isBuffer(body)) {
      const receipt = store.metadata<Json[]>("sandboxCopies")![0]!;
      assert.ok(
        [receipt.volumeId, receipt.sessionId].includes(body.id),
        "Identity must be committed before admission",
      );
      const record: Json = { ...body, state: "ready" };
      if (kind === "volumes")
        Object.assign(record, {
          cloneFrom: null,
          busy: false,
          attachedTo: null,
        });
      if (kind === "sessions") {
        Object.assign(record, { parentSessionId: null });
        Object.assign(state.records.volumes.get(body.volumeId)!, {
          busy: true,
          attachedTo: body.id,
        });
      }
      records.set(body.id, record);
      return record;
    }
    const record = records.get(id!);
    if (!record)
      throw new SandcrabError(
        404,
        `${kind === "volumes" ? "VOLUME" : kind === "sessions" ? "SESSION" : "RUN"}_NOT_FOUND`,
      );
    if (method === "GET" && !action) return record;
    if (action === "forget") {
      records.delete(id!);
      return { forgotten: true };
    }
    if (kind === "sessions" && action === "close") {
      record.state = "closed";
      Object.assign(state.records.volumes.get(record.volumeId)!, {
        busy: false,
        attachedTo: null,
      });
      for (const run of state.records.runs.values())
        if (run.state === "running")
          Object.assign(run, {
            state: "cancelled",
            result: { exitCode: 137, stdout: "", stderr: "", truncated: false },
          });
      return record;
    }
    if (kind === "sessions" && action === "renew") return record;
    if (kind === "volumes" && action === "delete") {
      if (record.busy) throw new SandcrabError(409, "VOLUME_BUSY");
      record.state = "deleted";
      return record;
    }
    if (
      kind === "sessions" &&
      method === "PUT" &&
      action === "files?path=corpus.bundle&mode=384&replace=false"
    ) {
      assert.deepEqual(body, bundle);
      assert.equal(record.state, "ready");
      return {
        path: "corpus.bundle",
        bytes: bundle.length,
        sha256: createHash("sha256").update(bundle).digest("hex"),
      };
    }
    if (
      kind === "sessions" &&
      action === "commands" &&
      !Buffer.isBuffer(body)
    ) {
      if (record.state !== "ready")
        throw new SandcrabError(409, "SESSION_NOT_READY");
      assert.ok(
        store.metadata<Json[]>("sandboxCopies")![0]!.runs.includes(body.id),
        "Command identity must be committed before dispatch",
      );
      const run: Json = {
        id: body.id,
        serverId: record.serverId,
        sessionId: id,
        profile: record.profile,
        state: "succeeded",
        result: {
          exitCode: 0,
          stdout:
            body.command === "large-output" ? "x".repeat(24_000) + "\n" : "ok",
          stderr: "",
          truncated: false,
        },
      };
      if (body.command === "timeout") {
        run.state = "timed_out";
        run.result.exitCode = 124;
        record.state = "expired";
      }
      if (body.command === "pending") {
        run.state = "running";
        run.result = null;
      }
      state.records.runs.set(body.id, run);
      return run;
    }
    throw new Error("Unexpected public request");
  }
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(socket, resolve);
  });
  const api = new Sandcrab(socket);
  // The actual daemon needs a root-owned Unix socket; this synthetic peer also runs on Windows pipes.
  api.connect = () => api.json("GET", "/v1/info");
  return {
    ...state,
    state,
    store,
    manifest,
    settings,
    api,
    worker: new Worker(settings, store, manifest, api),
    async close() {
      server.closeAllConnections();
      await new Promise<void>((resolve) => server.close(() => resolve()));
      store.close();
      rmSync(root, { recursive: true, force: true });
    },
  };
}
