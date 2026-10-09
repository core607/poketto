export function init({ seed }) {
  return { seed, key: false, lit: false, escaped: false };
}

export function observe(state) {
  if (state.escaped) return { text: "The gate opens. A new street waits outside.", actions: [], done: true };
  const actions = [];
  if (!state.key) actions.push({ id: "inspect", label: "Look beneath the bench" });
  if (!state.lit) actions.push({ id: "light", label: "Light the lantern" });
  if (state.key && state.lit) actions.push({ id: "open", label: "Open the gate" });
  return { text: `${state.lit ? "The lantern glows." : "The lantern is dark."} ${state.key ? "A brass key rests in your hand." : "A bench stands by a locked gate."}`, actions, done: false };
}

export function act(state, action) {
  if (action === "inspect") return { ...state, key: true };
  if (action === "light") return { ...state, lit: true };
  if (action === "open" && state.key && state.lit) return { ...state, escaped: true };
  throw new Error("That move is not available");
}
