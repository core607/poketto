import { constants } from "node:fs";
import { lstat, open } from "node:fs/promises";
import { request } from "node:http";
import { dirname } from "node:path";
import type { Readable } from "node:stream";
import type { Json } from "./types.js";

export interface SandboxApi {
  connect(): Promise<Json>;
  json(
    method: string,
    path: string,
    body?: object,
    signal?: AbortSignal,
  ): Promise<Json>;
  upload(
    id: string,
    path: string,
    bytes: number,
    signal: AbortSignal,
  ): Promise<Json>;
}

export class SandcrabError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
  ) {
    super(`Sandcrab refused request: ${code} (${status})`);
  }
}

/** Fixed-length public HTTP; a failed exchange never resubmits a mutation. */
export function exchange(
  socketPath: string,
  method: string,
  path: string,
  body: Buffer | Readable | undefined,
  bytes: number,
  signal?: AbortSignal,
  timeout = 5_000,
): Promise<Json> {
  return new Promise((resolve, reject) => {
    const controller = new AbortController();
    const timer = setTimeout(
      () => controller.abort(new Error("Sandcrab request timed out")),
      timeout,
    );
    const combined = signal
      ? AbortSignal.any([signal, controller.signal])
      : controller.signal;
    const req = request(
      {
        socketPath,
        method,
        path,
        agent: false,
        maxHeaderSize: 8192,
        signal: combined,
        headers:
          body === undefined
            ? {}
            : {
                "Content-Length": bytes,
                "Content-Type": Buffer.isBuffer(body)
                  ? "application/json"
                  : "application/octet-stream",
              },
      },
      (response) => {
        const length = Number(response.headers["content-length"]);
        const declared = response.rawHeaders.filter(
          (_, i, all) =>
            i % 2 === 0 && all[i]!.toLowerCase() === "content-length",
        ).length;
        if (
          declared !== 1 ||
          !Number.isSafeInteger(length) ||
          length < 1 ||
          length > 8 * 1024 * 1024 ||
          response.headers["transfer-encoding"]
        )
          response.destroy(new Error("Invalid Sandcrab response framing"));
        const chunks: Buffer[] = [];
        let received = 0;
        response.on("data", (chunk: Buffer) => {
          received += chunk.length;
          if (received > length)
            response.destroy(new Error("Oversized Sandcrab response"));
          else chunks.push(chunk);
        });
        response.on("error", reject);
        response.on("end", () => {
          try {
            if (received !== length || !response.complete)
              throw new Error("Incomplete Sandcrab response");
            let value: Json;
            try {
              value = JSON.parse(Buffer.concat(chunks).toString("utf8"));
            } catch {
              throw new Error("Malformed Sandcrab response");
            }
            if (!value || typeof value !== "object" || Array.isArray(value))
              throw new Error("Invalid Sandcrab record");
            const status = response.statusCode ?? 0;
            if (status < 200 || status >= 300) {
              const code = value.error?.code;
              throw new SandcrabError(
                status,
                typeof code === "string" && /^[A-Z_]{1,64}$/.test(code)
                  ? code
                  : "INVALID_ERROR",
              );
            }
            resolve(value);
          } catch (error) {
            reject(error);
          }
        });
      },
    );
    req.on("error", reject);
    req.on("close", () => {
      clearTimeout(timer);
      if (body && !Buffer.isBuffer(body)) body.destroy();
    });
    if (body && !Buffer.isBuffer(body)) {
      body.on("error", (error) => req.destroy(error));
      body.pipe(req);
    } else req.end(body);
  });
}

export class Sandcrab implements SandboxApi {
  constructor(private socket: string) {}
  async connect() {
    const parent = await lstat(dirname(this.socket));
    const socket = await lstat(this.socket);
    if (
      !parent.isDirectory() ||
      parent.uid !== 0 ||
      parent.mode & 0o022 ||
      !socket.isSocket() ||
      socket.uid !== 0
    )
      throw new Error(
        "Sandcrab socket must be in a protected root-owned directory",
      );
    return this.json("GET", "/v1/info");
  }
  json(method: string, path: string, body?: object, signal?: AbortSignal) {
    const buffer =
      body === undefined ? undefined : Buffer.from(JSON.stringify(body));
    if (buffer && buffer.length > 65536)
      throw new Error("Sandcrab control request exceeds 65536 bytes");
    return exchange(
      this.socket,
      method,
      path,
      buffer,
      buffer?.length ?? 0,
      signal,
    );
  }
  async upload(id: string, path: string, bytes: number, signal: AbortSignal) {
    const file = await open(
      path,
      constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0),
    );
    try {
      const stat = await file.stat();
      if (
        !stat.isFile() ||
        stat.size !== bytes ||
        bytes < 1 ||
        bytes > 256 * 1024 * 1024
      )
        throw new Error("Corpus bundle does not match its declared size");
      return await exchange(
        this.socket,
        "PUT",
        `/v1/sessions/${id}/files?path=corpus.bundle&mode=384&replace=false`,
        file.createReadStream({ autoClose: false, start: 0, end: bytes - 1 }),
        bytes,
        signal,
        120_000,
      );
    } finally {
      await file.close();
    }
  }
}
