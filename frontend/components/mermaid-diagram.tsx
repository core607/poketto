"use client";
import { useEffect, useId, useState } from "react";

/**
 * A Mermaid diagram drawn in the browser. Mermaid loads only on pages that contain a diagram, runs
 * with `securityLevel: "strict"` (no click handlers, HTML labels escaped), and the page CSP still
 * refuses inline script. Until it draws, or if it fails, readers see the diagram source as code.
 */
export function MermaidDiagram({ source }: { source: string }) {
  const id = "mermaid-" + useId().replace(/[^\w-]/g, "");
  const dark = useDarkTheme();
  const [svg, setSvg] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    import("mermaid")
      .then(async ({ default: mermaid }) => {
        mermaid.initialize({
          startOnLoad: false,
          securityLevel: "strict",
          theme: dark ? "dark" : "neutral",
          fontFamily: "inherit",
        });
        const drawn = await mermaid.render(id, source);
        if (live) setSvg(drawn.svg);
      })
      .catch(() => {
        if (live) setSvg(null);
      });
    return () => {
      live = false;
    };
  }, [id, source, dark]);
  return svg ? (
    <div
      className="mermaid-diagram"
      role="img"
      aria-label="流程图"
      dangerouslySetInnerHTML={{ __html: svg }}
    />
  ) : (
    <pre className="mermaid-source">
      <code>{source}</code>
    </pre>
  );
}

function useDarkTheme() {
  const [dark, setDark] = useState(false);
  useEffect(() => {
    const query = matchMedia("(prefers-color-scheme: dark)");
    const update = () => {
      const chosen = document.documentElement.dataset.theme;
      setDark(chosen ? chosen === "dark" : query.matches);
    };
    update();
    query.addEventListener("change", update);
    const observer = new MutationObserver(update);
    observer.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ["data-theme"],
    });
    return () => {
      query.removeEventListener("change", update);
      observer.disconnect();
    };
  }, []);
  return dark;
}
