import { DatabaseSync } from "node:sqlite";
import { EventEmitter } from "node:events";
import type { Event, Run } from "./types.js";

export class Store extends EventEmitter {
  readonly db: DatabaseSync;
  constructor(path: string) {
    super();
    this.db = new DatabaseSync(path);
    this.db.exec(`PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL;
      CREATE TABLE IF NOT EXISTS runs(id TEXT PRIMARY KEY, created TEXT NOT NULL, body TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS events(seq INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, time TEXT NOT NULL, type TEXT NOT NULL, data TEXT NOT NULL);
      CREATE INDEX IF NOT EXISTS events_run ON events(run_id, seq);
      CREATE TABLE IF NOT EXISTS metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);`);
  }
  get(id: string): Run {
    const row = this.db.prepare("SELECT body FROM runs WHERE id=?").get(id);
    if (!row) throw new Error("Run not found");
    return JSON.parse(String(row.body));
  }
  list(): Run[] {
    return this.db
      .prepare("SELECT body FROM runs ORDER BY created DESC")
      .all()
      .map((r) => JSON.parse(String(r.body)));
  }
  save(run: Run, type: string, data: unknown = {}) {
    run.updatedAt = new Date().toISOString();
    this.db.exec("BEGIN IMMEDIATE");
    let event: Event;
    try {
      this.db
        .prepare(
          "INSERT INTO runs VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET body=excluded.body",
        )
        .run(run.id, run.createdAt, JSON.stringify(run));
      const inserted = this.db
        .prepare("INSERT INTO events(run_id,time,type,data) VALUES(?,?,?,?)")
        .run(run.id, run.updatedAt, type, JSON.stringify(data));
      event = {
        seq: Number(inserted.lastInsertRowid),
        runId: run.id,
        time: run.updatedAt,
        type,
        data,
      };
      this.db.exec("COMMIT");
    } catch (error) {
      this.db.exec("ROLLBACK");
      throw error;
    }
    this.emit("event", event);
  }
  events(id: string, after = 0): Event[] {
    return this.db
      .prepare(
        "SELECT * FROM events WHERE run_id=? AND seq>? ORDER BY seq LIMIT 1000",
      )
      .all(id, after)
      .map((r) => ({
        seq: Number(r.seq),
        runId: String(r.run_id),
        time: String(r.time),
        type: String(r.type),
        data: JSON.parse(String(r.data)),
      }));
  }
  metadata<T>(key: string): T | undefined {
    const row = this.db
      .prepare("SELECT value FROM metadata WHERE key=?")
      .get(key);
    return row ? JSON.parse(String(row.value)) : undefined;
  }
  setMetadata(key: string, value: unknown) {
    this.db
      .prepare(
        "INSERT INTO metadata VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
      )
      .run(key, JSON.stringify(value));
  }
  recover() {
    for (const run of this.list()) {
      if (run.status === "running" || run.status === "queued") {
        for (const event of this.events(run.id)) {
          if (event.type !== "provider_started") continue;
          const usage = event.data as Run["usage"][number];
          if (!run.usage.some((call) => call.callId === usage.callId))
            run.usage.push({ ...usage, status: "uncertain", cost: null });
        }
        for (const result of Object.values(run.results))
          if (result.status === "running") result.status = "interrupted";
        run.status = "interrupted";
        run.error =
          "Service restarted. A provider request may have been billed; retry explicitly.";
        this.save(run, "interrupted", { message: run.error });
      }
    }
  }
  close() {
    this.db.close();
  }
}
