"use client";
type TreeNode = { children: Map<string, TreeNode>; path?: string };
export function FileTree({
  paths,
  selected,
  busy,
  onOpen,
}: {
  paths: string[];
  selected?: string;
  busy: boolean;
  onOpen: (path: string) => void;
}) {
  const root: TreeNode = { children: new Map() };
  for (const path of paths) {
    let node = root;
    for (const segment of path.split("/")) {
      if (!node.children.has(segment))
        node.children.set(segment, { children: new Map() });
      node = node.children.get(segment)!;
    }
    node.path = path;
  }
  function branch(node: TreeNode): React.ReactNode {
    return Array.from(node.children)
      .sort(
        ([a, x], [b, y]) =>
          Number(Boolean(x.path)) - Number(Boolean(y.path)) ||
          a.localeCompare(b, "zh-CN"),
      )
      .map(([name, child]) =>
        child.path ? (
          <button
            key={name}
            className={selected === child.path ? "selected" : ""}
            title={child.path}
            disabled={busy}
            onClick={() => onOpen(child.path!)}
          >
            <span aria-hidden>▤</span>
            <span>{name}</span>
          </button>
        ) : (
          <details className="tree-directory" key={name} open>
            <summary>{name}</summary>
            <div>{branch(child)}</div>
          </details>
        ),
      );
  }
  return branch(root);
}
