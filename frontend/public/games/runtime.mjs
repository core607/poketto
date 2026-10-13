// Shared by the browser rule worker and the isolated Linux game job. This validates
// the wire contract; the enclosing worker/OS sandbox owns code isolation.
const encoder = new TextEncoder();
export const limits = Object.freeze({
  bundle: 384 * 1024,
  state: 32 * 1024,
  observation: 32 * 1024,
});

function require(value, message) {
  if (!value) throw new Error(message);
}

export function jsonValue(value, maximum, depth = 0) {
  require(depth <= 16, "Game JSON exceeds 16 levels");
  if (value === null || typeof value === "boolean") return value;
  if (typeof value === "number") {
    require(Number.isFinite(value), "Game numbers must be finite");
    return value;
  }
  if (typeof value === "string") {
    require(value.isWellFormed(), "Game text must be well formed");
    require(encoder.encode(JSON.stringify(value)).length <=
      maximum, "Game text exceeds its bound");
    return value;
  }
  require(typeof value === "object", "Game values must be JSON");
  require(Array.isArray(value) ||
    Object.getPrototypeOf(value) ===
      Object.prototype, "Game objects must be plain JSON");
  const keys = Object.keys(value);
  require(keys.length <= 2048, "Game object has too many entries");
  const result = Array.isArray(value) ? [] : Object.create(null);
  for (const key of keys) {
    require(encoder.encode(key).length <= 256 &&
      key.isWellFormed(), "Invalid game field name");
    Object.defineProperty(result, key, {
      value: jsonValue(value[key], maximum, depth + 1),
      enumerable: true,
    });
  }
  require(encoder.encode(JSON.stringify(result)).length <=
    maximum, "Game JSON exceeds its byte bound");
  return JSON.parse(JSON.stringify(result));
}

function text(value, maximum, name) {
  require(typeof value === "string" &&
    value.isWellFormed(), `${name} must be text`);
  require(encoder.encode(value).length <=
    maximum, `${name} exceeds its byte bound`);
  return value;
}

export function observation(value) {
  const result = jsonValue(value, limits.observation);
  require(result !== null &&
    !Array.isArray(result) &&
    typeof result === "object", "Observation must be an object");
  require(Object.keys(result).every((key) =>
    ["text", "actions", "done"].includes(key),
  ), "Unknown observation field");
  text(result.text, 24 * 1024, "Observation text");
  require(Array.isArray(result.actions) &&
    result.actions.length <= 32, "At most 32 game actions are allowed");
  const ids = new Set();
  for (const item of result.actions) {
    require(item !== null &&
      typeof item === "object" &&
      !Array.isArray(item), "Action must be an object");
    require(Object.keys(item).every((key) =>
      ["id", "label"].includes(key),
    ), "Unknown action field");
    text(item.id, 128, "Action ID");
    require(!/[\u0000-\u001f\u007f-\u009f]/.test(
      item.id,
    ), "Action IDs cannot contain control characters");
    text(item.label, 256, "Action label");
    require(item.id.length > 0 &&
      item.label.length > 0 &&
      !ids.has(item.id), "Game actions need unique nonempty IDs and labels");
    ids.add(item.id);
  }
  require(result.done === undefined ||
    typeof result.done === "boolean", "done must be boolean");
  require(!result.done ||
    result.actions.length === 0, "A completed game cannot offer actions");
  return result;
}

function presentation(value, resources) {
  const result = jsonValue(value, limits.observation);
  require(result !== null &&
    typeof result === "object" &&
    !Array.isArray(result), "Presentation must be an object");
  require(Object.keys(result).every((key) =>
    ["heading", "paragraphs", "image"].includes(key),
  ), "Unknown presentation field");
  text(result.heading, 512, "Presentation heading");
  require(Array.isArray(result.paragraphs) &&
    result.paragraphs.length <= 32, "At most 32 presentation paragraphs");
  result.paragraphs.forEach((item) =>
    text(item, 4096, "Presentation paragraph"),
  );
  if (result.image !== undefined && result.image !== null) {
    text(result.image, 256, "Presentation image");
    require(Object.hasOwn(
      resources,
      result.image,
    ), "Presentation image must be a declared resource");
    require(["image/png", "image/jpeg", "image/webp"].includes(
      resources[result.image].mediaType,
    ), "Presentation image type is not supported");
  }
  return result;
}

async function load(source) {
  text(source, 256 * 1024, "Rule module");
  // A package is self-contained. Runtime network and local-file permissions are
  // denied outside this function; import() is not itself a security boundary.
  return import(
    "data:text/javascript;charset=utf-8," + encodeURIComponent(source)
  );
}

export async function runGame(bundle, request) {
  jsonValue(bundle, limits.bundle);
  require(bundle.protocol === 1, "Unsupported game protocol");
  const resources = bundle.resources ?? {};
  const rules = await load(bundle.source);
  require(["init", "observe", "act"].every(
    (name) => typeof rules[name] === "function",
  ), "Game exports init, observe and act");
  require(["init", "observe", "act"].includes(
    request.mode,
  ), "Unknown game operation");
  const context = { resources: jsonValue(resources, limits.bundle) };
  let state;
  if (request.mode === "init") {
    require(Number.isInteger(request.seed) &&
      request.seed >= 0 &&
      request.seed <= 0xffffffff, "A 32-bit seed is required");
    state = rules.init({ ...context, seed: request.seed });
  } else {
    state = jsonValue(request.state, limits.state);
    if (request.mode === "act") {
      const before = observation(
        rules.observe(jsonValue(state, limits.state), context),
      );
      require(before.actions.some(
        (item) => item.id === request.action,
      ), "The current game does not offer that action");
      state = rules.act(state, request.action, context);
    }
  }
  state = jsonValue(state, limits.state);
  const seen = observation(
    rules.observe(jsonValue(state, limits.state), context),
  );
  let view = null;
  if (bundle.presentation) {
    const display = await load(bundle.presentation);
    require(typeof display.present ===
      "function", "Presentation exports present");
    view = presentation(
      display.present(
        jsonValue(state, limits.state),
        jsonValue(seen, limits.observation),
        context,
      ),
      resources,
    );
  }
  return { state, observation: seen, presentation: view };
}
