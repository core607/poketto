import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { createInterface } from "node:readline";
import { randomUUID } from "node:crypto";
import type { Config } from "./config.js";
import type { Evidence } from "./types.js";

export class Data {
  private process?: ChildProcessWithoutNullStreams;
  private pending = new Map<
    string,
    {
      resolve: (v: any) => void;
      reject: (e: Error) => void;
      timer: NodeJS.Timeout;
    }
  >();
  constructor(private config: Config) {}
  private start() {
    if (this.process) return;
    const child = spawn(
      this.config.python,
      ["-u", "python/bridge.py", this.config.data],
      {
        env: {
          PATH: process.env.PATH,
          SystemRoot: process.env.SystemRoot,
          PYTHONIOENCODING: "utf-8",
          OMP_NUM_THREADS: "1",
        },
        windowsHide: true,
      },
    );
    this.process = child;
    const fail = (message: string) => {
      if (this.process === child) this.stop(new Error(message));
    };
    child.stdin.on("error", () => fail("Corpus helper input failed"));
    child.stderr.resume();
    createInterface({ input: child.stdout }).on("line", (line) => {
      try {
        const message = JSON.parse(line),
          request = this.pending.get(message.id);
        if (!request) return;
        this.pending.delete(message.id);
        clearTimeout(request.timer);
        if (message.error) request.reject(new Error(message.error));
        else request.resolve(message.result);
      } catch {
        fail("Malformed corpus helper response");
      }
    });
    child.on("error", () => fail("Corpus helper could not start"));
    child.on("exit", () => {
      fail("Corpus helper exited");
    });
  }
  call<T>(request: object): Promise<T> {
    this.start();
    return new Promise((resolve, reject) => {
      const id = randomUUID();
      const timer = setTimeout(
        () => this.stop(new Error("Corpus helper timed out")),
        120_000,
      );
      this.pending.set(id, { resolve, reject, timer });
      const child = this.process!;
      if (child.stdin.destroyed) {
        this.stop(new Error("Corpus helper input is closed"));
        return;
      }
      child.stdin.write(JSON.stringify({ ...request, id }) + "\n", (error) => {
        if (error && this.process === child)
          this.stop(new Error("Corpus helper input failed"));
      });
    });
  }
  query(qid: string) {
    return this.call<string>({ op: "query", qid });
  }
  documents(ids: string[]) {
    return this.call<Evidence[]>({ op: "documents", ids });
  }
  stop(error = new Error("Corpus helper stopped")) {
    const child = this.process;
    this.process = undefined;
    child?.kill();
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timer);
      pending.reject(error);
    }
    this.pending.clear();
  }
}
