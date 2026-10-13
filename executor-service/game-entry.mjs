import { readFileSync } from "node:fs";
import { runGame } from "./game-runtime.mjs";

// Executed only by the unprivileged launcher inside SRT. No application imports,
// credentials or repository bridge are part of this process.
const input = readFileSync(process.argv[2]);
if (input.length > 512 * 1024) throw new Error("Game input exceeds its byte bound");
const { bundle, request } = JSON.parse(input.toString("utf8"));
const result = await runGame(bundle, request);
process.stdout.write(JSON.stringify(result));
