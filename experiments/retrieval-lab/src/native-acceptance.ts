import assert from "node:assert/strict";
import { config, readJson } from "./config.js";
import { Store } from "./store.js";
import { Worker, type CorpusManifest } from "./worker.js";
import { join } from "node:path";
import { spawn } from "node:child_process";

if (process.env.LAB_ENV_FILE) process.loadEnvFile(process.env.LAB_ENV_FILE);
const settings = config();
const manifest = readJson<
  CorpusManifest & { documentPaths: Record<string, string> }
>(join(settings.data, "corpus-manifest.json"));
const store = new Store(join(settings.data, "native-acceptance.db"));
const worker = new Worker(settings, store, manifest);
const q = (s: string) => "'" + s.replaceAll("'", "'\\''") + "'";
try {
  const session = await worker.open(AbortSignal.timeout(180_000));
  try {
    const scan = await session.execute(
      `python -c ${q("import pathlib,json; p=pathlib.Path('.'); files=[x for x in p.glob('corpus/**/*.md') if x.name!='README.md']; print(json.dumps({'documents':len(files),'navigation':len(list(p.glob('**/README.md'))),'queries':list(map(str,p.glob('**/*.trec')))+list(map(str,p.glob('**/*.db')))}))")}`,
    );
    assert.equal(scan.exitCode, 0);
    const counts = JSON.parse(scan.stdout);
    assert.equal(counts.documents, manifest.documents);
    assert.deepEqual(counts.queries, []);
    assert.ok(counts.navigation > 32);
    const boundary = await session.execute(
      `python -c ${q("import pathlib,socket; blocked=[]\nfor p in ['/etc/retrieval-lab/private.pem','/var/lib/retrieval-lab/data/fiqa.db']:\n try: pathlib.Path(p).read_bytes()\n except (PermissionError,FileNotFoundError): blocked.append(p)\nassert len(blocked)==2\ns=socket.socket();s.settimeout(2)\ntry: s.connect(('1.1.1.1',443));raise AssertionError('network available')\nexcept OSError: print('HOST_AND_NETWORK_DENIED')")}`,
    );
    assert.equal(boundary.exitCode, 0);
    assert.match(boundary.stdout, /HOST_AND_NETWORK_DENIED/);
    const longOutput = await session.execute(
      `python -c ${q('print("x" * 24000)')}`,
    );
    assert.equal(longOutput.stdoutTruncated, true);
    const page = await session.artifact(
      longOutput.artifacts.stdout.artifactId,
      16384,
    );
    assert.equal(
      Buffer.from(page.data, "base64").toString("utf8"),
      "x".repeat(7616) + "\n",
    );
    const path = Object.values(manifest.documentPaths)[0]!;
    assert.equal(
      (await session.execute(`printf local-mutation > ${q(path)}`)).exitCode,
      0,
    );
    console.log(
      JSON.stringify({
        test: "complete corpus, navigation, label separation, host and network boundary",
        result: "PASS",
        ...counts,
      }),
    );
  } finally {
    await session.close();
  }
  const second = await worker.open(AbortSignal.timeout(180_000));
  try {
    const path = Object.values(manifest.documentPaths)[0]!;
    const result = await second.execute(
      `test "$(cat ${q(path)})" != local-mutation`,
    );
    assert.equal(result.exitCode, 0);
    const timeout = await second.execute("sleep 35");
    assert.equal(timeout.timedOut, true);
    assert.equal(
      (await second.execute("printf recovered")).stdout,
      "recovered",
    );
    console.log(
      JSON.stringify({
        test: "fresh question copy and timeout containment",
        result: "PASS",
      }),
    );
  } finally {
    await second.close();
  }
  assert.deepEqual(store.metadata("workerCopies"), []);
  const child = spawn(
    process.execPath,
    ["dist/test/worker-process-fixture.js"],
    {
      env: {
        PATH: process.env.PATH,
        LAB_DATA: settings.data,
        LAB_WORKER_SOCKET: settings.workerSocket,
        LAB_SIGNING_KEY: settings.signingKey,
        LAB_EXPORT_ROOT: settings.exportRoot,
      },
      stdio: ["ignore", "pipe", "inherit"],
    },
  );
  await new Promise<void>((resolve, reject) => {
    const timer = setTimeout(() => {
      child.kill("SIGKILL");
      reject(new Error("Process-loss fixture did not open a lease"));
    }, 150_000);
    child.once("error", reject);
    child.stdout.once("data", (bytes) => {
      if (!bytes.toString().includes("READY_FOR_PROCESS_LOSS")) return;
      clearTimeout(timer);
      child.kill("SIGKILL");
    });
    child.once("exit", (_, signal) => {
      clearTimeout(timer);
      signal === "SIGKILL"
        ? resolve()
        : reject(new Error("Unexpected fixture exit"));
    });
  });
  assert.equal(store.metadata<unknown[]>("workerCopies")!.length, 1);
  await worker.cleanup();
  assert.deepEqual(store.metadata("workerCopies"), []);
  console.log(
    JSON.stringify({
      test: "process loss retains cleanup identity and releases copy/export",
      result: "PASS",
    }),
  );
} finally {
  store.close();
}
