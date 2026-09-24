import {
  createServer,
  type IncomingMessage,
  type ServerResponse,
} from "node:http";
import { readFileSync, mkdirSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import { config, type Config } from "./config.js";
import { Store } from "./store.js";
import { Engine } from "./engine.js";
import type { Event, Json, Run } from "./types.js";

function json(response: ServerResponse, value: unknown, status = 200) {
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
  });
  response.end(JSON.stringify(value));
}
async function body(request: IncomingMessage): Promise<Json> {
  if (!request.headers["content-type"]?.startsWith("application/json"))
    throw new Error("JSON request required");
  const chunks: Buffer[] = [];
  let bytes = 0;
  for await (const chunk of request) {
    bytes += chunk.length;
    if (bytes > 32768) throw new Error("Request exceeds 32 KiB");
    chunks.push(chunk);
  }
  const result = JSON.parse(Buffer.concat(chunks).toString("utf8"));
  if (!result || typeof result !== "object" || Array.isArray(result))
    throw new Error("JSON object required");
  return result;
}
function brief(run: Run) {
  return {
    id: run.id,
    createdAt: run.createdAt,
    status: run.status,
    query: run.query,
    qid: run.qid,
    split: run.split,
    batchId: run.batchId,
    retryOf: run.retryOf,
    fingerprint: run.fingerprint,
    results: Object.fromEntries(
      Object.entries(run.results).map(([route, value]) => [
        route,
        {
          status: value.status,
          metrics: value.metrics,
          limited: value.limited,
          error: value.error,
        },
      ]),
    ),
  };
}
const csvCell = (value: unknown) => {
  let text = String(value ?? "");
  if (/^[=+@-]/.test(text)) text = "'" + text;
  return '"' + text.replaceAll('"', '""') + '"';
};
export function createLab(engine: Engine, store: Store, settings: Config) {
  const server = createServer(async (req, res) => {
    res.setHeader("Cache-Control", "no-store");
    res.setHeader("X-Content-Type-Options", "nosniff");
    res.setHeader(
      "Content-Security-Policy",
      "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'",
    );
    const host = req.headers.host;
    const allowed = [
      `127.0.0.1:${settings.port}`,
      `localhost:${settings.port}`,
    ];
    if (!host || !allowed.includes(host)) {
      json(res, { error: "Use the loopback origin" }, 403);
      return;
    }
    const url = new URL(req.url ?? "/", `http://${host}`);
    if (req.method !== "GET" && req.headers.origin !== `http://${host}`) {
      json(res, { error: "Same-origin request required" }, 403);
      return;
    }
    try {
      const path = url.pathname;
      if (
        req.method === "GET" &&
        ["/", "/app.js", "/style.css"].includes(path)
      ) {
        const file = path === "/" ? "index.html" : path.slice(1);
        res.setHeader(
          "Content-Type",
          file.endsWith(".js")
            ? "text/javascript; charset=utf-8"
            : file.endsWith(".css")
              ? "text/css"
              : "text/html; charset=utf-8",
        );
        res.end(readFileSync(join("public", file)));
        return;
      }
      if (path === "/api/status" && req.method === "GET") {
        json(res, engine.summary());
        return;
      }
      if (path === "/api/runs" && req.method === "GET") {
        json(res, store.list().map(brief));
        return;
      }
      if (path === "/api/runs" && req.method === "POST") {
        const input = await body(req);
        if (
          input.qid !== undefined &&
          (typeof input.qid !== "string" ||
            !/^[A-Za-z0-9_-]{1,80}$/.test(input.qid))
        )
          throw new Error("Invalid query ID");
        if (input.query !== undefined && typeof input.query !== "string")
          throw new Error("Invalid question");
        const id = await engine.create({
          query: input.query,
          qid: input.qid,
          routes: input.routes,
          clarify: input.clarify,
        });
        json(res, { id }, 201);
        return;
      }
      if (path === "/api/compatibility" && req.method === "POST") {
        await body(req);
        json(res, await engine.compatibility());
        return;
      }
      if (path === "/api/batches" && req.method === "POST") {
        const input = await body(req);
        json(res, { batchId: await engine.batch(input.split) }, 201);
        return;
      }
      if (path === "/api/cancel-all" && req.method === "POST") {
        await body(req);
        engine.cancelAll();
        json(res, { ok: true });
        return;
      }
      if (path === "/api/export" && req.method === "GET") {
        const runs = store
          .list()
          .filter(
            (r) =>
              !url.searchParams.has("batch") ||
              r.batchId === url.searchParams.get("batch"),
          );
        if (url.searchParams.get("format") === "csv") {
          const rows: unknown[][] = [
            [
              "run_id",
              "retry_of",
              "batch_id",
              "qid",
              "route",
              "status",
              "ndcg10",
              "recall10",
              "tools",
              "limited",
              "retrieval_ms",
              "answer_ms",
              "usd",
              "cny",
              "unknown_cost_calls",
              "human_citation_support",
              "human_answer_notes",
            ],
          ];
          for (const run of runs)
            for (const route of run.routes) {
              const result = run.results[route];
              rows.push([
                run.id,
                run.retryOf,
                run.batchId,
                run.qid,
                route,
                result?.status ?? run.status,
                result?.metrics?.ndcg10,
                result?.metrics?.recall10,
                result?.tools,
                result?.limited,
                result?.retrievalMs,
                result?.answerMs,
                run.usage
                  .filter((u) => u.currency === "USD" && u.route === route)
                  .reduce((sum, u) => sum + (u.cost ?? 0), 0),
                run.usage
                  .filter((u) => u.currency === "CNY" && u.route === route)
                  .reduce((sum, u) => sum + (u.cost ?? 0), 0),
                run.usage.filter((u) => u.cost === null && u.route === route)
                  .length,
                "",
                "",
              ]);
            }
          res.setHeader("Content-Type", "text/csv; charset=utf-8");
          res.setHeader(
            "Content-Disposition",
            'attachment; filename="retrieval-results.csv"',
          );
          res.end(
            rows.map((row) => row.map(csvCell).join(",")).join("\n") + "\n",
          );
        } else {
          res.setHeader(
            "Content-Disposition",
            'attachment; filename="retrieval-results.json"',
          );
          json(res, {
            configuration: engine.summary(),
            runs: runs.map((run) => ({ ...run, events: store.events(run.id) })),
            interpretation:
              "Public financial benchmark; test subset is not the full leaderboard. Citations need human support review. No complete reference answers are supplied.",
          });
        }
        return;
      }
      const match =
        /^\/api\/runs\/([0-9a-f-]{36})(?:\/(events|reply|cancel|retry))?$/.exec(
          path,
        );
      if (match) {
        const id = match[1]!,
          action = match[2];
        store.get(id);
        if (!action && req.method === "GET") {
          json(res, store.get(id));
          return;
        }
        if (action === "events" && req.method === "GET") {
          const after = Number(
            req.headers["last-event-id"] ?? url.searchParams.get("after") ?? 0,
          );
          if (!Number.isSafeInteger(after) || after < 0)
            throw new Error("Invalid event cursor");
          res.writeHead(200, {
            "Content-Type": "text/event-stream",
            Connection: "keep-alive",
          });
          let cursor = after;
          const send = (event: Event) => {
            if (event.runId !== id || event.seq <= cursor) return;
            cursor = event.seq;
            if (
              !res.write(`id: ${event.seq}\ndata: ${JSON.stringify(event)}\n\n`)
            )
              res.destroy();
          };
          store.on("event", send);
          for (const event of store.events(id, after)) send(event);
          const keepalive = setInterval(
            () => res.write(": keepalive\n\n"),
            15_000,
          );
          res.on("close", () => {
            clearInterval(keepalive);
            store.off("event", send);
          });
          return;
        }
        if (req.method === "POST") {
          const input = await body(req);
          if (action === "reply") {
            if (typeof input.reply !== "string")
              throw new Error("Reply is required");
            engine.reply(id, input.reply);
          } else if (action === "cancel") engine.cancel(id);
          else if (action === "retry") {
            json(res, { id: engine.retry(id) }, 201);
            return;
          } else throw new Error("Unknown run action");
          json(res, { ok: true });
          return;
        }
      }
      json(res, { error: "Not found" }, 404);
    } catch (error) {
      if (!res.headersSent)
        json(
          res,
          { error: error instanceof Error ? error.message : "Request failed" },
          400,
        );
      else res.end();
    }
  });
  server.requestTimeout = 240_000;
  return server;
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  if (process.env.LAB_ENV_FILE) process.loadEnvFile(process.env.LAB_ENV_FILE);
  const settings = config();
  mkdirSync(settings.data, { recursive: true, mode: 0o700 });
  const store = new Store(join(settings.data, "runs.db"));
  store.recover();
  const engine = new Engine(settings, store);
  const server = createLab(engine, store, settings);
  server.listen(settings.port, "127.0.0.1", () =>
    console.log(`Retrieval lab: http://127.0.0.1:${settings.port}`),
  );
  const stop = () => {
    for (const run of store.list())
      if (run.status === "running") engine.cancel(run.id);
    server.close();
    server.closeAllConnections();
    engine.data.stop();
  };
  process.on("SIGINT", stop);
  process.on("SIGTERM", stop);
}
