import { jsonValue, limits, observation } from "./runtime.mjs";

const title = document.getElementById("title");
const scene = document.getElementById("scene");
const actions = document.getElementById("actions");
const status = document.getElementById("status");
const runtime = new URL("./runtime.mjs", import.meta.url).href;
let bundle,
  state,
  channel,
  busy = false;
let pendingRequest,
  checkId = 0;

function render(result) {
  const nextState = jsonValue(result.state, limits.state);
  const seen = observation(result.observation);
  const view = result.presentation
    ? jsonValue(result.presentation, limits.observation)
    : null;
  title.textContent =
    typeof view?.heading === "string" ? view.heading : "口袋小游戏";
  scene.replaceChildren();
  const paragraphs = Array.isArray(view?.paragraphs)
    ? view.paragraphs
    : [seen.text];
  for (const text of paragraphs.slice(0, 32)) {
    if (typeof text !== "string") throw new Error("Invalid presentation");
    const paragraph = document.createElement("p");
    paragraph.textContent = text;
    scene.append(paragraph);
  }
  const resource =
    view?.image && Object.hasOwn(bundle.resources, view.image)
      ? bundle.resources[view.image]
      : null;
  if (
    resource &&
    ["image/png", "image/jpeg", "image/webp"].includes(resource.mediaType)
  ) {
    const image = document.createElement("img");
    image.alt = "游戏画面";
    image.src = `data:${resource.mediaType};base64,${resource.data}`;
    scene.append(image);
  }
  actions.replaceChildren();
  for (const action of seen.actions) {
    const button = document.createElement("button");
    button.textContent = action.label;
    button.addEventListener("click", () =>
      step({ mode: "act", state, action: action.id }),
    );
    actions.append(button);
  }
  state = nextState;
  channel.postMessage({ kind: "state", state, observation: seen });
}

function step(request) {
  if (busy) return;
  busy = true;
  for (const button of actions.querySelectorAll("button"))
    button.disabled = true;
  pendingRequest = request;
  status.textContent = "正在确认游戏版本…";
  channel.postMessage({ kind: "check", id: ++checkId });
}

function execute(request) {
  status.textContent = "游戏进行中…";
  const source = `import { runGame } from ${JSON.stringify(runtime)};
    onmessage=async(event)=>{try{postMessage({ok:true,result:await runGame(event.data.bundle,event.data.request)})}
    catch{postMessage({ok:false})}};`;
  const url = URL.createObjectURL(
    new Blob([source], { type: "text/javascript" }),
  );
  let worker;
  try {
    worker = new Worker(url, { type: "module" });
  } catch {
    URL.revokeObjectURL(url);
    busy = false;
    for (const button of actions.querySelectorAll("button"))
      button.disabled = false;
    status.textContent = "浏览器无法启动隔离游戏。";
    channel.postMessage({ kind: "error" });
    return;
  }
  let settled = false;
  const finish = (result) => {
    if (settled) return;
    settled = true;
    clearTimeout(timer);
    worker.terminate();
    URL.revokeObjectURL(url);
    busy = false;
    for (const button of actions.querySelectorAll("button"))
      button.disabled = false;
    try {
      if (!result?.ok) throw new Error("Game failed");
      render(result.result);
      status.textContent = "";
    } catch {
      status.textContent = "这一步未完成，保留上一次存档。可以重试或退出游戏。";
      channel.postMessage({ kind: "error" });
    }
  };
  const timer = setTimeout(() => finish(null), 5000);
  worker.onmessage = (event) => finish(event.data);
  worker.onerror = () => finish(null);
  worker.postMessage({ bundle, request });
}

window.addEventListener("message", (event) => {
  if (
    event.source !== parent ||
    event.data?.kind !== "poketto-game-connect" ||
    channel ||
    event.ports.length !== 1
  )
    return;
  channel = event.ports[0];
  channel.onmessage = ({ data }) => {
    if (data?.kind === "checked" && pendingRequest && data.id === checkId) {
      const request = pendingRequest;
      pendingRequest = undefined;
      if (data.allowed) execute(request);
      else {
        busy = false;
        for (const button of actions.querySelectorAll("button"))
          button.disabled = false;
        status.textContent =
          "当前游戏版本已不可用，或连接中断。进度仍然保留，请刷新页面后核对。";
        channel.postMessage({ kind: "error" });
      }
      return;
    }
    if (data?.kind !== "load" || busy) return;
    try {
      bundle = jsonValue(data.bundle, limits.bundle);
      if (data.state !== undefined)
        step({ mode: "observe", state: data.state });
      else {
        const seed = crypto.getRandomValues(new Uint32Array(1))[0];
        step({ mode: "init", seed });
      }
    } catch {
      status.textContent = "游戏包或存档无效。";
      channel.postMessage({ kind: "error" });
    }
  };
  channel.start();
  channel.postMessage({ kind: "ready" });
});
