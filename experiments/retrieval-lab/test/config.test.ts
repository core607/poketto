import { test } from "node:test";
import assert from "node:assert/strict";
import { config } from "../src/config.js";

test("invalid listener ports fail configuration before the server starts", () => {
  const previous = process.env.LAB_PORT;
  try {
    for (const port of ["", "0", "-1", "1.5", "65536", "abc"]) {
      process.env.LAB_PORT = port;
      assert.throws(() => config(), /LAB_PORT/);
    }
    delete process.env.LAB_PORT;
    assert.equal(config().port, 38470);
    process.env.LAB_PORT = "38472";
    assert.equal(config().port, 38472);
  } finally {
    if (previous === undefined) delete process.env.LAB_PORT;
    else process.env.LAB_PORT = previous;
  }
});
