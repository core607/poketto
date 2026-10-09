import type { QaActivity } from "./qa";

function record(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}

function parse(text: string) {
  try {
    return record(JSON.parse(text));
  } catch {
    return {};
  }
}

function label(value: unknown) {
  if (typeof value !== "string") return "";
  const text = value.replace(/\s+/g, " ").trim();
  return text.length > 80 ? `${text.slice(0, 80)}…` : text;
}

export function toolSummary(entry: QaActivity, fallback: string) {
  const input = parse(entry.input);
  const output = parse(entry.output);
  const query = label(input.query);
  const tag = label(input.tag);
  let action = fallback;
  if (entry.name === "search") {
    action = query ? `搜索「${query}」` : "浏览公开文章";
    if (tag) action += ` · #${tag}`;
  }
  if (entry.state === "FAILED") return `${action} → 未完成`;
  if (entry.state === "RUNNING") return action;

  switch (entry.name) {
    case "search": {
      if (output.refineQuery === true)
        return `${action} → 结果较多，请缩小范围`;
      if (typeof output.total !== "number" || output.total < 0) return action;
      const titles = Array.isArray(output.items)
        ? output.items
            .slice(0, 2)
            .map((item) => label(record(item).title))
            .filter(Boolean)
        : [];
      const found =
        output.total === 0 ? "没有匹配的文章" : `找到 ${output.total} 篇`;
      return `${action} → ${found}${titles.length ? `：${titles.join("、")}${output.total > titles.length ? "等" : ""}` : ""}`;
    }
    case "read": {
      const page = record(output.page);
      const title = label(record(page.article).title);
      return title
        ? `阅读《${title}》${page.nextOffset != null ? " → 还有后续内容" : ""}`
        : action;
    }
    case "clarify":
      return label(output.question)
        ? `确认范围：${label(output.question)}`
        : action;
    case "answer":
      if (input.status === "insufficient_evidence")
        return "未找到足以回答的公开证据";
      return Array.isArray(output.paragraphs)
        ? `整理答案与出处 → ${output.paragraphs.length} 段回答`
        : action;
    default:
      return action;
  }
}
