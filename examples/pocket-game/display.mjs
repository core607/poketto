export function present(state, observation) {
  return { heading: state.escaped ? "Beyond the gate" : "The pocket lantern", paragraphs: [observation.text] };
}
