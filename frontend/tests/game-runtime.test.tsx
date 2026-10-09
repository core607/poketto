import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
// The .mjs module is also staged into the Linux game worker without transpilation.
// @ts-expect-error Plain JS portable runtime has no generated declaration file.
import { runGame, jsonValue } from "../public/games/runtime.mjs";

test("one rule package round-trips a save and carries the observation with every move", async () => {
  const source = await readFile("../examples/pocket-game/rules.mjs", "utf8");
  const presentation = await readFile(
    "../examples/pocket-game/display.mjs",
    "utf8",
  );
  const bundle = { protocol: 1, source, presentation, resources: {} };
  let result = await runGame(bundle, { mode: "init", seed: 42 });
  assert.equal(result.state.seed, 42);
  assert.equal(result.presentation.heading, "The pocket lantern");
  await assert.rejects(
    runGame(bundle, { mode: "act", state: result.state, action: "open" }),
    /does not offer/,
  );
  for (const action of ["inspect", "light", "open"]) {
    result = await runGame(bundle, {
      mode: "act",
      state: JSON.parse(JSON.stringify(result.state)),
      action,
    });
  }
  assert.equal(result.observation.done, true);
  assert.deepEqual(result.observation.actions, []);
  assert.match(result.observation.text, /gate opens/);
  assert.deepEqual(
    await runGame(bundle, { mode: "observe", state: result.state }),
    result,
  );
});

test("the runtime rejects malformed state, oversized multibyte output and async rules", async () => {
  assert.throws(() => jsonValue({ bad: Infinity }, 1024), /finite/);
  assert.throws(() => jsonValue({ bad: "\ud800" }, 1024), /well formed/);
  assert.throws(() => jsonValue({ bad: undefined }, 1024), /JSON/);
  assert.throws(() => jsonValue({ text: "猫".repeat(400) }, 1024), /bound/);
  const source =
    "export async function init(){return {}}; export function observe(){return {text:'x',actions:[]}}; export function act(s){return s}";
  await assert.rejects(
    runGame({ protocol: 1, source }, { mode: "init", seed: 1 }),
    /plain JSON/,
  );
  const invalid =
    "export function init(){return {}}; export function observe(){return {text:'x',actions:[{id:'x',label:'x'},{id:'x',label:'again'}]}}; export function act(s){return s}";
  await assert.rejects(
    runGame({ protocol: 1, source: invalid }, { mode: "init", seed: 1 }),
    /unique/,
  );
});
