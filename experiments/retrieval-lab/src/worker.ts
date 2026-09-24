import { connect } from "node:net";
import {
  createHash,
  createPrivateKey,
  randomUUID,
  sign,
  type KeyObject,
} from "node:crypto";
import { copyFile, lstat, readFile, unlink } from "node:fs/promises";
import { join, dirname } from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import type { Config } from "./config.js";
import type { Store } from "./store.js";
import type { Json } from "./types.js";

const frameLimit = 1_048_576;
type Identity = {
  principalId: string;
  accountId: string;
  workspaceId: string;
  appBootId: string;
  serverSessionHash: string;
  leaseId: string;
};
export type CorpusManifest = {
  commit: string;
  bundleSha256: string;
  bundleBytes: number;
  documents: number;
  files: number;
  preparationSeconds: number;
  textBytes: number;
};
type Receipt = {
  identity: Identity;
  copyId: string;
  commit: string;
  exportId: string;
};

export class Worker {
  private key?: KeyObject;
  private hello?: Json;
  private appBootId = randomUUID();
  constructor(
    private config: Config,
    private store: Store,
    private manifest: CorpusManifest,
  ) {}
  private async rpc(value: object): Promise<Json> {
    const parent = await lstat(dirname(this.config.workerSocket));
    const socketInfo = await lstat(this.config.workerSocket);
    if (
      !parent.isDirectory() ||
      parent.uid !== 0 ||
      parent.mode & 0o022 ||
      !socketInfo.isSocket() ||
      socketInfo.uid !== 0
    )
      throw new Error(
        "Executor socket is not in a protected root-owned directory",
      );
    const bytes = Buffer.from(JSON.stringify(value));
    if (bytes.length > frameLimit)
      throw new Error("Executor request exceeds frame bound");
    return new Promise((resolve, reject) => {
      const socket = connect(this.config.workerSocket);
      let buffer = Buffer.alloc(0);
      const timer = setTimeout(
        () => socket.destroy(new Error("Executor reply timed out")),
        125_000,
      );
      socket.on("connect", () => {
        const header = Buffer.alloc(4);
        header.writeUInt32BE(bytes.length);
        socket.write(Buffer.concat([header, bytes]));
      });
      socket.on("data", (chunk) => {
        buffer = Buffer.concat([buffer, chunk]);
        if (buffer.length < 4) return;
        const length = buffer.readUInt32BE();
        if (length > frameLimit || length === 0) {
          socket.destroy(new Error("Invalid executor frame"));
          return;
        }
        if (buffer.length >= 4 + length) {
          try {
            resolve(
              JSON.parse(buffer.subarray(4, 4 + length).toString("utf8")),
            );
          } catch {
            reject(new Error("Malformed executor response"));
          }
          socket.destroy();
        }
      });
      socket.on("error", reject);
      socket.on("close", () => {
        clearTimeout(timer);
        reject(new Error("Executor connection ended"));
      });
    });
  }
  private async initialize() {
    if (!this.key)
      this.key = createPrivateKey(await readFile(this.config.signingKey));
    this.hello = await this.rpc({ version: 1, operation: "HELLO" });
    if (
      !this.hello.ok ||
      this.hello.version !== 1 ||
      !this.hello.workerBootId ||
      !Number.isInteger(this.hello.leaseSeconds) ||
      this.hello.leaseSeconds < 5
    )
      throw new Error("Incompatible executor HELLO");
    for (const marker of [
      "diskCopyProtocol",
      "leaseSandboxProtocol",
      "artifactProtocol",
      "codeActProtocol",
    ])
      if (this.hello[marker] !== 1)
        throw new Error(`Executor is missing ${marker}`);
  }
  async send(identity: Identity, operation: string, data: object = {}) {
    if (!this.hello || !this.key)
      throw new Error("Executor is not initialized");
    const now = Math.floor(Date.now() / 1000);
    const raw = Buffer.from(
      JSON.stringify({
        ...identity,
        version: 1,
        workerBootId: this.hello.workerBootId,
        operation,
        requestId: randomUUID(),
        issuedAt: now,
        expiresAt: now + this.hello.leaseSeconds,
        data,
      }),
    );
    const reply = await this.rpc({
      payload: raw.toString("base64url"),
      signature: sign(null, raw, this.key).toString("base64url"),
    });
    if (!reply.ok)
      throw new Error(
        `Executor refused ${operation}: ${reply.error?.code ?? reply.error ?? reply.code ?? "unknown"}`,
      );
    return reply;
  }
  private receipts() {
    return this.store.metadata<Receipt[]>("workerCopies") ?? [];
  }
  private removeReceipt(copyId: string) {
    this.store.setMetadata(
      "workerCopies",
      this.receipts().filter((r) => r.copyId !== copyId),
    );
  }
  async cleanup() {
    await this.initialize();
    for (const receipt of this.receipts()) await this.release(receipt);
  }
  private async release(receipt: Receipt) {
    const deadline = Date.now() + 60_000;
    while (true) {
      const closed = await this.send(receipt.identity, "CLOSE", {
        reason: "session_closed",
      });
      if (closed.state === "CLOSED") break;
      if (Date.now() >= deadline)
        throw new Error("Executor cleanup remains unconfirmed");
      await delay(200);
    }
    const disposed = await this.send(receipt.identity, "DISCARD", {
      copyId: receipt.copyId,
      scope: "full",
      commit: receipt.commit,
    });
    if (!["DISCARDED", "ABSENT"].includes(disposed.state))
      throw new Error("Executor copy disposal remains unconfirmed");
    await unlink(
      join(this.config.exportRoot, `${receipt.exportId}.bundle`),
    ).catch((error: NodeJS.ErrnoException) => {
      if (error.code !== "ENOENT") throw error;
    });
    this.removeReceipt(receipt.copyId);
  }
  async open(signal: AbortSignal) {
    await this.cleanup();
    signal.throwIfAborted();
    const identity: Identity = {
      principalId: randomUUID(),
      accountId: randomUUID(),
      workspaceId: randomUUID(),
      appBootId: this.appBootId,
      leaseId: randomUUID(),
      serverSessionHash: createHash("sha256")
        .update(randomUUID())
        .digest("hex"),
    };
    const receipt: Receipt = {
      identity,
      copyId: randomUUID(),
      commit: this.manifest.commit,
      exportId: randomUUID(),
    };
    this.store.setMetadata("workerCopies", [...this.receipts(), receipt]);
    const exportId = receipt.exportId,
      exportPath = join(this.config.exportRoot, `${exportId}.bundle`);
    let timer: NodeJS.Timeout | undefined;
    let renewing = false;
    const lost = new AbortController();
    const combined = AbortSignal.any([signal, lost.signal]);
    const cancel = () => {
      void this.send(identity, "CLOSE", { reason: "cancelled" }).catch(
        () => {},
      );
    };
    combined.addEventListener("abort", cancel, { once: true });
    const close = async () => {
      combined.removeEventListener("abort", cancel);
      try {
        await this.release(receipt);
      } finally {
        if (timer) clearInterval(timer);
      }
    };
    try {
      await copyFile(join(this.config.data, "corpus.bundle"), exportPath);
      const opening = this.send(identity, "OPEN", {
        copyId: receipt.copyId,
        scope: "full",
        exportId,
        bundleSha256: this.manifest.bundleSha256,
        bundleBytes: this.manifest.bundleBytes,
        commit: this.manifest.commit,
      });
      timer = setInterval(
        () => {
          if (renewing) return;
          renewing = true;
          void this.send(identity, "RENEW")
            .then(
              () => {},
              () => lost.abort(new Error("Executor lease renewal failed")),
            )
            .finally(() => {
              renewing = false;
            });
        },
        Math.max(1000, Number(this.hello!.renewAfterSeconds) * 1000),
      );
      const opened = await opening;
      if (opened.state !== "READY")
        throw new Error("Executor did not become ready");
      combined.throwIfAborted();
      return {
        execute: async (command: string) => {
          combined.throwIfAborted();
          const reply = await this.send(identity, "EXEC", {
            executionId: randomUUID(),
            commit: receipt.commit,
            command,
            timeoutMillis: 30_000,
          });
          combined.throwIfAborted();
          return reply.result;
        },
        artifact: async (artifactId: string, offset: number) => {
          combined.throwIfAborted();
          return this.send(identity, "ARTIFACT_READ", {
            artifactId,
            offset,
            limit: 16_384,
          });
        },
        close,
      };
    } catch (error) {
      await close();
      throw error;
    }
  }
}
