import { randomUUID } from "node:crypto";
import { join } from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { experiment, type Config } from "./config.js";
import { Sandcrab, SandcrabError, type SandboxApi } from "./sandcrab.js";
import type { Store } from "./store.js";
import type { Json } from "./types.js";

export type CorpusManifest = {
  commit: string;
  bundleSha256: string;
  bundleBytes: number;
  documents: number;
  files: number;
  preparationSeconds: number;
  textBytes: number;
};
type Admission = "planned" | "pending" | "observed";
type Receipt = {
  version: 2;
  socket: string;
  serverId: string;
  profile: string;
  volumeId: string;
  volume: Admission | "deleted";
  sessionId: string;
  session: Admission;
  runs: string[];
};
const volumeBytes = 2 * 1024 * 1024 * 1024;
const volumeInodes = 150_000;
const outputBytes = 1024 * 1024;
const pageBytes = 16_384;
const sessionTerminal = new Set(["closed", "expired", "failed"]);
const runTerminal = new Set([
  "succeeded",
  "failed",
  "cancelled",
  "timed_out",
  "output_limit",
  "resource_limit",
]);
const uuid = (value: unknown): value is string =>
  typeof value === "string" &&
  /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value);
function ensure(value: unknown, message: string): asserts value {
  if (!value) throw new Error(message);
}

export class Worker {
  private active = false;
  constructor(
    private config: Config,
    private store: Store,
    private manifest: CorpusManifest,
    private api: SandboxApi = new Sandcrab(config.workerSocket),
  ) {}

  private receipts() {
    const legacy = this.store.metadata<unknown>("workerCopies");
    ensure(
      legacy === undefined || (Array.isArray(legacy) && legacy.length === 0),
      "Retained signed-worker copies need operator cleanup before native adoption",
    );
    const stored = this.store.metadata<Receipt[]>("sandboxCopies");
    const receipts = stored === undefined ? [] : stored;
    ensure(
      Array.isArray(receipts) && receipts.length <= 1,
      "Invalid Sandcrab cleanup inventory",
    );
    for (const r of receipts) {
      ensure(
        r.socket === this.config.workerSocket,
        "Sandcrab cleanup belongs to another socket; restore its original service before switching",
      );
      ensure(
        r.version === 2 &&
          [r.serverId, r.volumeId, r.sessionId].every(uuid) &&
          /^[a-z][a-z0-9-]{0,47}$/.test(r.profile),
        "Invalid Sandcrab cleanup identity",
      );
      ensure(
        ["planned", "pending", "observed", "deleted"].includes(r.volume) &&
          ["planned", "pending", "observed"].includes(r.session) &&
          Array.isArray(r.runs) &&
          r.runs.length <= experiment.maxToolCalls + 1 &&
          r.runs.every(uuid) &&
          new Set(r.runs).size === r.runs.length,
        "Invalid Sandcrab cleanup state",
      );
    }
    return receipts;
  }
  private save(receipt: Receipt) {
    this.store.setMetadata("sandboxCopies", [receipt]);
  }
  private async info() {
    const info = await this.api.connect();
    ensure(
      info.apiVersion === 1 && uuid(info.serverId),
      "Incompatible Sandcrab API",
    );
    return info;
  }
  async cleanup() {
    ensure(!this.active, "A retrieval sandbox is active");
    this.active = true;
    try {
      for (const receipt of this.receipts()) await this.release(receipt);
    } finally {
      this.active = false;
    }
  }
  private async wait(
    read: () => Promise<Json>,
    ready: (value: Json) => boolean,
    deadline: number,
    signal?: AbortSignal,
  ) {
    while (true) {
      signal?.throwIfAborted();
      ensure(Date.now() < deadline, "Sandcrab operation remains unconfirmed");
      const value = await read();
      if (ready(value)) return value;
      await delay(50, undefined, { signal });
    }
  }
  private async absent(path: string, signal?: AbortSignal) {
    try {
      return await this.api.json("GET", path, undefined, signal);
    } catch (error) {
      const code = path.startsWith("/v1/volumes/")
        ? "VOLUME_NOT_FOUND"
        : path.startsWith("/v1/sessions/")
          ? "SESSION_NOT_FOUND"
          : "RUN_NOT_FOUND";
      if (
        error instanceof SandcrabError &&
        error.status === 404 &&
        error.code === code
      )
        return null;
      throw error;
    }
  }
  private session(value: Json, receipt: Receipt) {
    ensure(
      value.id === receipt.sessionId &&
        value.serverId === receipt.serverId &&
        value.profile === receipt.profile &&
        value.volumeId === receipt.volumeId &&
        value.parentSessionId === null,
      "Sandcrab session changed identity",
    );
    return value;
  }
  private volume(value: Json, receipt: Receipt) {
    ensure(
      value.id === receipt.volumeId &&
        value.profile === receipt.profile &&
        value.byteLimit === volumeBytes &&
        value.inodeLimit === volumeInodes &&
        value.cloneFrom === null,
      "Sandcrab volume changed plan",
    );
    return value;
  }
  private run(value: Json, receipt: Receipt, id: string) {
    ensure(
      value.id === id &&
        value.serverId === receipt.serverId &&
        value.sessionId === receipt.sessionId &&
        value.profile === receipt.profile,
      "Sandcrab command changed identity",
    );
    return value;
  }

  private async release(receipt: Receipt) {
    const info = await this.info();
    const deadline = Date.now() + 60_000;
    const signal = AbortSignal.timeout(60_000);
    const json = (method: string, path: string, body?: object) =>
      this.api.json(method, path, body, signal);
    const absent = (path: string) => this.absent(path, signal);
    const sessions = `/v1/sessions/${receipt.sessionId}`;
    if (receipt.session !== "planned") {
      const current = await absent(sessions);
      if (current) {
        this.session(current, receipt);
        await json("POST", `${sessions}/close`, {});
        await this.wait(
          async () => this.session(await json("GET", sessions), receipt),
          (s) => sessionTerminal.has(s.state),
          deadline,
          signal,
        );
      } else
        ensure(
          receipt.session === "observed" || info.serverId !== receipt.serverId,
          "Session admission is uncertain; retain its cleanup receipt",
        );
    }
    // A contained session cannot admit a late command or upload. Durable volumes are reconciled separately.
    for (const id of receipt.runs) {
      const path = `/v1/runs/${id}`;
      const current = await absent(path);
      if (!current) continue;
      this.run(current, receipt, id);
      if (!runTerminal.has(current.state)) {
        await json("POST", `${path}/cancel`, {});
        await this.wait(
          async () => this.run(await json("GET", path), receipt, id),
          (r) => runTerminal.has(r.state),
          deadline,
          signal,
        );
      }
      await json("POST", `${path}/forget`, {});
    }
    if (receipt.session !== "planned") {
      const current = await absent(sessions);
      if (current) {
        this.session(current, receipt);
        await json("POST", `${sessions}/forget`, {});
      }
    }
    if (receipt.volume !== "planned") {
      const path = `/v1/volumes/${receipt.volumeId}`;
      const current = await absent(path);
      if (current) {
        this.volume(current, receipt);
        if (receipt.volume !== "deleted") {
          await json("POST", `${path}/delete`, {});
          await this.wait(
            async () => this.volume(await json("GET", path), receipt),
            (v) =>
              v.state === "deleted" &&
              v.busy === false &&
              v.attachedTo === null,
            deadline,
            signal,
          );
          receipt.volume = "deleted";
          this.save(receipt);
        } else {
          ensure(
            current.state === "deleted" &&
              current.busy === false &&
              current.attachedTo === null,
            "Previously deleted volume changed state; restore its original store",
          );
        }
        await json("POST", `${path}/forget`, {});
      } else {
        ensure(
          receipt.volume === "deleted",
          "Volume admission or disposal is uncertain; retain its cleanup receipt and inspect the original store",
        );
      }
    }
    this.store.setMetadata("sandboxCopies", []);
  }

  async open(signal: AbortSignal) {
    ensure(!this.active, "A retrieval sandbox is active");
    this.active = true;
    let receipt: Receipt | undefined;
    let timer: NodeJS.Timeout | undefined;
    let renewal: Promise<void> | undefined;
    let closed: Promise<void> | undefined;
    const lost = new AbortController();
    const combined = AbortSignal.any([signal, lost.signal]);
    const cancel = () => {
      void close().catch(() => {});
    };
    const close = () => {
      if (!closed)
        closed = (async () => {
          combined.removeEventListener("abort", cancel);
          if (timer) clearInterval(timer);
          await renewal;
          try {
            if (receipt) await this.release(receipt);
          } finally {
            this.active = false;
          }
        })();
      return closed;
    };
    try {
      for (const previous of this.receipts()) await this.release(previous);
      combined.throwIfAborted();
      const info = await this.info();
      const profile = info.profiles?.[this.config.workerProfile];
      ensure(
        Array.isArray(info.capabilities) &&
          ["runs", "sessions", "volumes", "files"].every((c) =>
            info.capabilities.includes(c),
          ),
        "Sandcrab lacks corpus capabilities",
      );
      ensure(
        profile?.network === "none" &&
          profile.hostCalls === false &&
          profile.timeoutMillis >= 120_000 &&
          profile.outputLimitBytes >= outputBytes &&
          profile.transferBytes >= 256 * 1024 * 1024 &&
          profile.sessionTtlMillis >= 60_000,
        "Sandcrab corpus profile does not satisfy the experiment bounds",
      );
      ensure(
        info.storage?.available === true &&
          info.storage.quotaEnforced === true &&
          info.storage.maxVolumeBytes >= volumeBytes &&
          info.storage.maxVolumeInodes >= volumeInodes,
        "Sandcrab corpus storage requires enforced quotas and sufficient capacity",
      );
      ensure(
        /^[a-f0-9]{40}$/.test(this.manifest.commit) &&
          /^[a-f0-9]{64}$/.test(this.manifest.bundleSha256) &&
          Number.isSafeInteger(this.manifest.bundleBytes) &&
          this.manifest.bundleBytes > 0 &&
          this.manifest.bundleBytes <= 256 * 1024 * 1024,
        "Invalid corpus bundle manifest",
      );
      receipt = {
        version: 2,
        socket: this.config.workerSocket,
        serverId: info.serverId,
        profile: this.config.workerProfile,
        volumeId: randomUUID(),
        volume: "planned",
        sessionId: randomUUID(),
        session: "planned",
        runs: [],
      };
      this.save(receipt);
      const r = receipt;
      const deadline = Date.now() + 120_000;
      r.volume = "pending";
      this.save(r);
      try {
        this.volume(
          await this.api.json(
            "POST",
            "/v1/volumes",
            {
              id: r.volumeId,
              serverId: r.serverId,
              profile: r.profile,
              byteLimit: volumeBytes,
              inodeLimit: volumeInodes,
            },
            combined,
          ),
          r,
        );
      } catch (error) {
        // STORAGE_CAPACITY explicitly refuses before allocating the durable identity.
        if (
          error instanceof SandcrabError &&
          error.status === 429 &&
          error.code === "STORAGE_CAPACITY"
        ) {
          r.volume = "planned";
          this.save(r);
        }
        throw error;
      }
      r.volume = "observed";
      this.save(r);
      await this.wait(
        async () =>
          this.volume(
            await this.api.json(
              "GET",
              `/v1/volumes/${r.volumeId}`,
              undefined,
              combined,
            ),
            r,
          ),
        (v) => {
          ensure(
            ["creating", "ready"].includes(v.state),
            "Corpus volume creation failed",
          );
          return v.state === "ready";
        },
        deadline,
        combined,
      );
      r.session = "pending";
      this.save(r);
      this.session(
        await this.api.json(
          "POST",
          "/v1/sessions",
          {
            id: r.sessionId,
            serverId: r.serverId,
            profile: r.profile,
            ttlMillis: 60_000,
            keepResult: true,
            volumeId: r.volumeId,
          },
          combined,
        ),
        r,
      );
      r.session = "observed";
      this.save(r);
      combined.addEventListener("abort", cancel, { once: true });
      timer = setInterval(() => {
        if (renewal) return;
        renewal = this.api
          .json(
            "POST",
            `/v1/sessions/${r.sessionId}/renew`,
            { ttlMillis: 60_000 },
            combined,
          )
          .then((value) => {
            this.session(value, r);
            ensure(
              value.state === "ready" || value.state === "busy",
              "Corpus session ended",
            );
          })
          .catch(() => lost.abort(new Error("Sandcrab session renewal failed")))
          .finally(() => {
            renewal = undefined;
          });
      }, 20_000);
      await this.wait(
        async () =>
          this.session(
            await this.api.json(
              "GET",
              `/v1/sessions/${r.sessionId}`,
              undefined,
              combined,
            ),
            r,
          ),
        (s) => {
          ensure(
            ["starting", "ready"].includes(s.state),
            "Corpus session initialization failed",
          );
          return s.state === "ready";
        },
        deadline,
        combined,
      );
      const uploaded = await this.api.upload(
        r.sessionId,
        join(this.config.data, "corpus.bundle"),
        this.manifest.bundleBytes,
        combined,
      );
      ensure(
        uploaded.path === "corpus.bundle" &&
          uploaded.bytes === this.manifest.bundleBytes &&
          uploaded.sha256 === this.manifest.bundleSha256,
        "Received corpus bundle differs from the prepared manifest",
      );
      let executing = false;
      let ended = false;
      const execute = async (command: string, timeoutMillis: number) => {
        combined.throwIfAborted();
        ensure(!closed && !executing, "Corpus session is closing or busy");
        ensure(!ended, "Corpus session ended; commands are never replayed");
        ensure(
          typeof command === "string" &&
            command.length > 0 &&
            !command.includes("\0") &&
            Buffer.byteLength(command) <= 16_384,
          "Invalid corpus shell command",
        );
        ensure(
          r.runs.length < experiment.maxToolCalls + 1,
          "Corpus command budget exhausted",
        );
        const id = randomUUID();
        r.runs.push(id);
        this.save(r);
        executing = true;
        try {
          this.run(
            await this.api.json(
              "POST",
              `/v1/sessions/${r.sessionId}/commands`,
              {
                id,
                serverId: r.serverId,
                command,
                keepResult: true,
                timeoutMillis,
                outputLimitBytes: outputBytes,
              },
              combined,
            ),
            r,
            id,
          );
          const result = await this.wait(
            async () =>
              this.run(
                await this.api.json(
                  "GET",
                  `/v1/runs/${id}`,
                  undefined,
                  combined,
                ),
                r,
                id,
              ),
            (run) => runTerminal.has(run.state),
            Date.now() + timeoutMillis + 15_000,
            combined,
          );
          if (
            [
              "timed_out",
              "cancelled",
              "output_limit",
              "resource_limit",
            ].includes(result.state) ||
            result.result?.code
          ) {
            ended = true;
            if (timer) clearInterval(timer);
          }
          return result;
        } finally {
          executing = false;
        }
      };
      const initialized = await execute(
        `git init -q . && git -c protocol.file.allow=always fetch --no-tags ./corpus.bundle HEAD && test "$(git rev-parse FETCH_HEAD)" = ${this.manifest.commit} && git checkout --detach -q ${this.manifest.commit} && rm corpus.bundle`,
        120_000,
      );
      ensure(
        initialized.state === "succeeded" &&
          initialized.result?.exitCode === 0 &&
          initialized.result.truncated === false,
        "Corpus Git initialization failed",
      );
      combined.throwIfAborted();
      const output = (run: Json, stream: "stdout" | "stderr") => {
        ensure(
          typeof run.result?.[stream] === "string" &&
            Buffer.byteLength(run.result[stream]) <= 3 * outputBytes,
          "Sandcrab did not retain valid command output",
        );
        return Buffer.from(run.result[stream]);
      };
      return {
        execute: async (command: string): Promise<Json> => {
          const run = await execute(command, experiment.commandMs);
          ensure(
            run.result && !run.result.code,
            "Sandcrab command failed without output",
          );
          const stdout = output(run, "stdout"),
            stderr = output(run, "stderr");
          const preview = (bytes: Buffer) =>
            new TextDecoder().decode(bytes.subarray(0, pageBytes), {
              stream: bytes.length > pageBytes,
            });
          return {
            state: run.state,
            exitCode: run.result.exitCode,
            timedOut: run.state === "timed_out",
            truncated: run.result.truncated,
            stdout: preview(stdout),
            stderr: preview(stderr),
            stdoutTruncated: stdout.length > pageBytes,
            stderrTruncated: stderr.length > pageBytes,
            artifacts: {
              stdout: { artifactId: `${run.id}:stdout`, bytes: stdout.length },
              stderr: { artifactId: `${run.id}:stderr`, bytes: stderr.length },
            },
          };
        },
        artifact: async (artifactId: string, offset: number): Promise<Json> => {
          combined.throwIfAborted();
          ensure(!closed, "Corpus session is closing");
          const [id, stream, extra] = artifactId.split(":");
          ensure(
            id &&
              r.runs.slice(1).includes(id) &&
              (stream === "stdout" || stream === "stderr") &&
              extra === undefined &&
              Number.isSafeInteger(offset) &&
              offset >= 0,
            "Invalid corpus output reference",
          );
          const run = this.run(
            await this.api.json("GET", `/v1/runs/${id}`, undefined, combined),
            r,
            id,
          );
          ensure(runTerminal.has(run.state), "Corpus output is incomplete");
          const bytes = output(run, stream);
          ensure(
            offset <= bytes.length,
            "Corpus output offset exceeds its length",
          );
          const end = Math.min(bytes.length, offset + pageBytes);
          return {
            artifactId,
            offset,
            totalBytes: bytes.length,
            nextOffset: end,
            eof: end === bytes.length,
            data: bytes.subarray(offset, end).toString("base64"),
          };
        },
        close,
      };
    } catch (error) {
      try {
        await close();
      } catch (cleanup) {
        throw new AggregateError(
          [error, cleanup],
          "Corpus initialization and cleanup remain unconfirmed",
        );
      }
      throw error;
    }
  }
}
