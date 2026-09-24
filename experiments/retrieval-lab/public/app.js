const $ = (id) => document.getElementById(id);
let selected, stream, status, batchSplit, refreshTimer;
let historyLimit = 100;
const labels = {
  queued: "等待中",
  running: "运行中",
  waiting: "等待澄清",
  completed: "已完成",
  failed: "失败",
  cancelled: "已取消",
  interrupted: "意外中断",
};
const el = (tag, text, className) => {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (className) node.className = className;
  return node;
};
async function api(path, input) {
  const response = await fetch(
    path,
    input === undefined
      ? {}
      : {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(input),
        },
  );
  const result = await response.json();
  if (!response.ok) throw new Error(result.error);
  return result;
}
async function guarded(action) {
  $("error").textContent = "";
  try {
    await action();
  } catch (error) {
    $("error").textContent = error.message;
  }
}
async function refresh() {
  status = await api("/api/status");
  $("readiness").textContent =
    `${status.fixture ? "模拟交互验收 · 无真实模型调用 · " : ""}${status.corpus.toLocaleString()} 篇文章 · 离线校准通过 · DeepSeek ${status.providers.deepseek ? "已配置" : "未配置"} · SiliconFlow ${status.providers.siliconflow ? "已配置" : "未配置"}`;
  $("estimate").textContent = status.estimate.estimated100Pairs
    ? `按 ${status.estimate.pricedPairs} 道开发题估算，100 题约 $${status.estimate.estimated100Pairs.USD.toFixed(4)} + ¥${status.estimate.estimated100Pairs.CNY.toFixed(4)}。`
    : "尚无完整计价的开发样本，正式批次保持关闭。";
  $("test-batch").disabled =
    status.estimate.pricedPairs < 20 || !status.estimate.estimated100Pairs;
  if ($("benchmark").options.length === 1)
    for (const qid of status.splits.dev) {
      const option = el("option", `FiQA 开发题 ${qid}`);
      option.value = qid;
      $("benchmark").append(option);
    }
  const runs = await api("/api/runs");
  $("history").replaceChildren();
  for (const run of runs.slice(0, historyLimit)) {
    const item = el(
      "button",
      undefined,
      "history-item" + (selected === run.id ? " selected" : ""),
    );
    item.append(
      el("span", run.query),
      el(
        "small",
        `${labels[run.status]} · ${new Date(run.createdAt).toLocaleTimeString()}`,
      ),
    );
    item.onclick = () => guarded(() => openRun(run.id));
    $("history").append(item);
  }
  if (runs.length > historyLimit) {
    const more = el("button", "加载更早的记录", "quiet");
    more.onclick = () => {
      historyLimit += 100;
      guarded(refresh);
    };
    $("history").append(more);
  }
}
function renderResult(route, result, included) {
  const target = $(route + "-result");
  target.replaceChildren(
    el("h2", route === "rag" ? "传统 RAG" : "Agentic search"),
  );
  if (!result) {
    target.append(
      el("p", included ? "等待这条路线开始。" : "本次未选择这条路线。", "hint"),
    );
    return;
  }
  target.append(
    el(
      "span",
      labels[result.status] + (result.limited ? " · 已达调查上限" : ""),
      "status",
    ),
  );
  const metrics = el("p", undefined, "metrics");
  if (result.metrics)
    metrics.append(
      el("span", `nDCG@10 ${result.metrics.ndcg10.toFixed(3)}`),
      el("span", `Recall@10 ${result.metrics.recall10.toFixed(3)}`),
    );
  if (result.retrievalMs !== undefined)
    metrics.append(
      el("span", `检索 ${(result.retrievalMs / 1000).toFixed(1)}s`),
    );
  if (route === "agentic")
    metrics.append(el("span", `${result.tools} 次工具调用`));
  target.append(metrics);
  if (result.error) target.append(el("p", result.error, "answer"));
  if (result.answer) {
    target.append(el("p", result.answer.answer, "answer"));
    for (const citation of result.answer.citations)
      target.append(el("blockquote", `[${citation.id}] ${citation.quote}`));
    target.append(el("p", result.answer.limitations, "hint"));
  }
  target.append(el("h3", `原始证据 · ${result.evidence.length}`));
  for (const doc of result.evidence) {
    const item = el("details", undefined, "evidence");
    item.append(
      el("summary", doc.id),
      el("p", doc.path, "hint"),
      el("pre", doc.text),
    );
    target.append(item);
  }
}
async function renderRun() {
  if (!selected) return;
  const run = await api("/api/runs/" + selected);
  $("active").hidden = false;
  $("run-title").textContent = run.query;
  $("run-status").textContent = labels[run.status];
  $("cancel").hidden = !["queued", "running", "waiting"].includes(run.status);
  $("retry").hidden = !["failed", "interrupted", "cancelled"].includes(
    run.status,
  );
  $("clarification").hidden = run.status !== "waiting";
  if (run.status === "waiting") {
    $("clarification-question").textContent = run.clarification.question;
    $("choices").replaceChildren();
    for (const text of run.clarification.options) {
      const button = el("button", text, "quiet");
      button.type = "button";
      button.onclick = () => {
        $("reply").value = text;
      };
      $("choices").append(button);
    }
  }
  for (const route of ["rag", "agentic"])
    renderResult(route, run.results[route], run.routes.includes(route));
  const costs = { USD: 0, CNY: 0 };
  let unknown = 0;
  for (const usage of run.usage) {
    if (usage.cost === null) unknown++;
    else costs[usage.currency] += usage.cost;
  }
  $("usage").textContent =
    `本次已知调用费用：$${costs.USD.toFixed(5)} + ¥${costs.CNY.toFixed(5)}${unknown ? `；${unknown} 次调用费用待确认` : ""}。失败或中断也可能已计费。`;
  if (run.error) $("error").textContent = run.error;
  if (
    ["completed", "failed", "cancelled", "interrupted"].includes(run.status)
  ) {
    stream?.close();
    await refresh();
  }
}
async function openRun(id) {
  selected = id;
  stream?.close();
  $("events").replaceChildren();
  $("reply").value = "";
  stream = new EventSource("/api/runs/" + id + "/events");
  stream.onmessage = (message) => {
    const event = JSON.parse(message.data);
    const block = el("details");
    block.append(
      el(
        "summary",
        `${new Date(event.time).toLocaleTimeString()} · ${event.type}`,
        "event-label",
      ),
      el("pre", JSON.stringify(event.data, null, 2), "event"),
    );
    $("events").append(block);
    clearTimeout(refreshTimer);
    refreshTimer = setTimeout(() => guarded(renderRun), 120);
  };
  await renderRun();
  await refresh();
}
$("question-form").onsubmit = (event) => {
  event.preventDefault();
  guarded(async () => {
    const routes = ["rag", "agentic"].filter((route) => $(route).checked);
    const qid = $("benchmark").value;
    const result = await api("/api/runs", {
      query: $("question").value,
      ...(qid ? { qid } : {}),
      routes,
      clarify: $("clarify").checked,
    });
    await openRun(result.id);
  });
};
$("benchmark").onchange = () => {
  const qid = $("benchmark").value;
  $("question").required = !qid;
  $("question").disabled = Boolean(qid);
  $("question").placeholder = qid
    ? "将使用该公开题目的原始文本"
    : "输入研究问题";
  $("clarify").disabled = Boolean(qid);
};
$("clarification").onsubmit = (event) => {
  event.preventDefault();
  guarded(async () => {
    await api("/api/runs/" + selected + "/reply", { reply: $("reply").value });
    await renderRun();
  });
};
$("cancel").onclick = () =>
  guarded(async () => {
    await api("/api/runs/" + selected + "/cancel", {});
    await renderRun();
  });
$("retry").onclick = () =>
  guarded(async () => {
    const result = await api("/api/runs/" + selected + "/retry", {});
    await openRun(result.id);
  });
$("refresh").onclick = () => guarded(refresh);
$("cancel-all").onclick = () =>
  guarded(async () => {
    await api("/api/cancel-all", {});
    await refresh();
    await renderRun();
  });
$("compatibility").onclick = () =>
  guarded(async () => {
    $("compatibility").disabled = true;
    try {
      const result = await api("/api/compatibility", {});
      $("error").textContent = result.passed
        ? "兼容性检查通过，可以使用自由问题。"
        : "兼容性未通过；公开题目仍可使用预计算向量。";
      await refresh();
    } finally {
      $("compatibility").disabled = false;
    }
  });
for (const split of ["dev", "test"])
  $(split + "-batch").onclick = () => {
    batchSplit = split;
    $("batch-title").textContent =
      split === "dev" ? "启动 20 题开发实验" : "启动 100 题正式对照";
    $("batch-estimate").textContent = $("estimate").textContent;
    $("batch-dialog").showModal();
  };
$("batch-dismiss").onclick = () => $("batch-dialog").close();
$("batch-confirm").onclick = () =>
  guarded(async () => {
    $("batch-dialog").close();
    await api("/api/batches", { split: batchSplit });
    await refresh();
  });
guarded(refresh);
