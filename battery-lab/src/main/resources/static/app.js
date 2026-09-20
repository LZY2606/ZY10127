const $ = (id) => document.getElementById(id);

let runs = [];
let currentRunId = null;
let detail = null;
let rules = [];

async function api(path, options = {}) {
  const res = await fetch(path, {
    headers: { "Content-Type": "application/json" },
    ...options,
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.error || ("HTTP " + res.status));
  }
  return res.status === 200 ? res.json() : null;
}

function fmtUah(uah) {
  return (uah / 1000).toFixed(3) + " mAh";
}

function fmtCe(bp) {
  if (bp < 0) return "—";
  return (bp / 100).toFixed(2) + " %";
}

async function loadCatalog() {
  const catalog = await api("/api/catalog");
  rules = catalog.rules;
  const sel = $("rule-picker");
  sel.innerHTML = "";
  for (const r of rules) {
    const opt = document.createElement("option");
    opt.value = r.id;
    opt.textContent = r.name + " " + r.version;
    sel.appendChild(opt);
  }
}

async function loadRuns(preferId) {
  runs = await api("/api/runs");
  const sel = $("run-picker");
  sel.innerHTML = "";
  for (const run of runs) {
    const opt = document.createElement("option");
    opt.value = run.id;
    opt.textContent = "#" + run.id + " " + run.deviceSerial + " " + run.status
      + " t=" + run.virtualNowMs;
    sel.appendChild(opt);
  }
  if (preferId) currentRunId = preferId;
  if (!currentRunId && runs.length) currentRunId = runs[0].id;
  if (currentRunId) sel.value = String(currentRunId);
}

async function loadDetail() {
  if (!currentRunId) {
    detail = null;
    render();
    return;
  }
  detail = await api("/api/runs/" + currentRunId);
  render();
}

function render() {
  renderGraph();
  renderPoints();
  renderConflicts();
  renderDerived();
  renderStatusLine();
}

function renderStatusLine() {
  if (!detail) {
    $("run-status").textContent = "尚未选择运行";
    return;
  }
  const r = detail.run;
  $("run-status").innerHTML =
    '运行 <b>#' + r.id + '</b> 状态 <span class="badge ' + r.status + '">' + r.status + '</span>'
    + ' · 虚拟时钟 ' + r.virtualNowMs + ' ms · 周期 ' + r.cycleNo
    + ' · 已确认 seq ' + r.lastSeqConfirmed + ' · 链路 ' + r.linkState
    + ' · 中断原因 ' + r.interruptReason;
}

function renderGraph() {
  const el = $("protocol-graph");
  el.innerHTML = "";
  if (!detail) return;
  let protocol;
  try {
    protocol = JSON.parse(detail.run.protocolSnapshotJson).steps;
  } catch (e) {
    return;
  }
  const openStep = detail.steps.filter((s) => s.exitTsMs == null).pop();
  protocol.forEach((s, i) => {
    const node = document.createElement("span");
    let cls = "step-node";
    if (openStep && openStep.stepIndex === i) cls += " active";
    else if (detail.steps.some((x) => x.stepIndex === i && x.exitTsMs != null)) cls += " done";
    node.className = cls;
    let detail2 = "";
    if (s.type === "REST") detail2 = " " + (s.durationMs / 60000) + "min";
    if (s.limitMv) detail2 = " " + (s.limitMv / 1000).toFixed(2) + "V";
    if (s.type === "LOOP") detail2 = " ×" + s.repetitions + "→" + s.jumpTo;
    node.textContent = (i + 1) + "." + s.type.replace(/_/g, " ") + detail2;
    el.appendChild(node);
    if (i < protocol.length - 1) {
      const arrow = document.createElement("span");
      arrow.className = "arrow";
      arrow.textContent = "→";
      el.appendChild(arrow);
    }
  });
}

function renderPoints() {
  const tbody = $("points-table").querySelector("tbody");
  tbody.innerHTML = "";
  if (!detail) return;
  const amended = new Set(detail.amendments.map((a) => a.seq));
  for (const p of detail.points) {
    const tr = document.createElement("tr");
    if (p.late) tr.classList.add("late");
    if (amended.has(p.seq)) tr.classList.add("amended");
    tr.innerHTML =
      "<td>" + p.seq + "</td>"
      + "<td>" + p.tsMs + "</td>"
      + "<td>" + p.voltageMv + "</td>"
      + "<td>" + p.currentMa + "</td>"
      + "<td>" + (p.temperatureCd / 10).toFixed(1) + "</td>"
      + "<td>" + ({ "1": "充", "-1": "放", "0": "静置" }[p.direction] || p.direction) + "</td>"
      + "<td>" + (p.late ? "⚠ 是" : "") + "</td>"
      + "<td>" + (amended.has(p.seq) ? "已采用修正" : "") + "</td>";
    tbody.appendChild(tr);
  }
}

function renderConflicts() {
  const el = $("conflicts");
  el.innerHTML = "";
  if (!detail) return;
  for (const c of detail.conflicts) {
    if (c.status !== "OPEN") continue;
    const card = document.createElement("div");
    card.className = "conflict-card";
    card.innerHTML =
      "<b>重复序号冲突 seq=" + c.seq + "</b><br>"
      + "已确认: ts=" + c.existingTsMs + " V=" + c.existingMv + " I=" + c.existingMa
      + " T=" + c.existingCd + " dir=" + c.existingDirection + "<br>"
      + "重复报: ts=" + c.duplicateTsMs + " V=" + c.duplicateMv + " I=" + c.duplicateMa
      + " T=" + c.duplicateCd + " dir=" + c.duplicateDirection
      + ' <button data-id="' + c.id + '" data-act="KEEP_EXISTING">保留已确认</button>'
      + ' <button data-id="' + c.id + '" data-act="ACCEPT_NEW">采用新载荷（记为修正）</button>';
    el.appendChild(card);
  }
  el.querySelectorAll("button").forEach((b) => {
    b.onclick = async () => {
      await api("/api/runs/conflicts/" + b.dataset.id + "/resolve", {
        method: "POST",
        body: JSON.stringify({ resolution: b.dataset.act }),
      });
      await refresh();
    };
  });
}

function renderDerived() {
  const el = $("derived");
  el.innerHTML = "";
  if (!detail) return;
  for (const d of detail.derivedVersions.slice().reverse()) {
    const v = d.version;
    const wrap = document.createElement("div");
    wrap.className = "cycle-card";
    const flags = JSON.parse(v.reviewFlagsJson || "[]");
    const head = document.createElement("div");
    head.className = "derived-head";
    head.innerHTML = "<span>派生版本 v" + v.versionNo + "</span>"
      + '<span class="badge ' + (v.status === "PUBLISHED" ? "COMPLETED"
          : v.status === "NEEDS_REVIEW" ? "PAUSED" : "CANCELLED") + '">' + v.status + "</span>"
      + '<span class="small">规则 #' + v.ruleVersionId + " · " + v.reason + "</span>"
      + flags.map((f) => '<span class="flag">' + f + "</span>").join("");
    wrap.appendChild(head);

    const table = document.createElement("table");
    table.innerHTML = "<thead><tr><th>周期</th><th>起 ts</th><th>止 ts</th>"
      + "<th>充电量</th><th>放电量</th><th>库仑效率</th><th>中断原因</th><th>排除区间</th>"
      + "<th>原始测点</th></tr></thead>";
    const tb = document.createElement("tbody");
    for (const c of d.cycles) {
      const tr = document.createElement("tr");
      tr.innerHTML =
        "<td>" + c.cycleNo + "</td>"
        + "<td>" + c.startTsMs + "</td>"
        + "<td>" + c.endTsMs + "</td>"
        + "<td>" + fmtUah(c.chargeCapUah) + "</td>"
        + "<td>" + fmtUah(c.dischargeCapUah) + "</td>"
        + '<td class="ce">' + fmtCe(c.coulombicEfficiencyBp) + "</td>"
        + "<td>" + c.interruptReason + "</td>"
        + "<td>" + c.excludedIntervalCount + "</td>"
        + "<td>" + c.rawPointCount + "</td>";
      tb.appendChild(tr);
    }
    table.appendChild(tb);
    wrap.appendChild(table);
    el.appendChild(wrap);
  }
}

async function refresh(preferId) {
  try {
    await loadRuns(preferId);
    await loadDetail();
  } catch (e) {
    alert(e.message);
  }
}

async function runAction(path, body) {
  try {
    const r = await api(path, body ? { method: "POST", body: JSON.stringify(body) } : { method: "POST" });
    $("tick-result").textContent = r && r.generated !== undefined
      ? "生成 " + r.generated + " / 送达 " + r.delivered + " / 缓冲 " + r.buffered
        + (r.blockedByConflict ? " / 冲突阻塞" : "") + (r.completed ? " / 已完成" : "")
      : "";
  } catch (e) {
    alert(e.message);
  }
  await refresh();
}

$("run-picker").onchange = async (e) => {
  currentRunId = Number(e.target.value);
  await loadDetail();
};
$("btn-create").onclick = async () => {
  const created = await api("/api/runs", {
    method: "POST",
    body: JSON.stringify({ deviceSerial: $("device-serial").value }),
  });
  await refresh(created.id);
};
$("btn-refresh").onclick = () => refresh();
$("btn-tick").onclick = () => runAction("/api/runs/" + currentRunId + "/tick", { tickMs: 300000 });
$("btn-tick-big").onclick = () => runAction("/api/runs/" + currentRunId + "/tick", { tickMs: 3600000 });
$("btn-pause").onclick = () => runAction("/api/runs/" + currentRunId + "/pause");
$("btn-resume").onclick = () => runAction("/api/runs/" + currentRunId + "/resume");
$("btn-cancel").onclick = () => runAction("/api/runs/" + currentRunId + "/cancel");
$("btn-fault").onclick = () => runAction("/api/runs/" + currentRunId + "/device-fault");
$("btn-offline").onclick = () => runAction("/api/runs/" + currentRunId + "/link/offline");
$("btn-online").onclick = () => runAction("/api/runs/" + currentRunId + "/link/online");
$("btn-lostack").onclick = () => runAction("/api/runs/" + currentRunId + "/inject/lost-ack");
$("btn-exclude").onclick = () => runAction("/api/runs/" + currentRunId + "/exclusions", {
  fromTsMs: Number($("ex-from").value),
  toTsMs: Number($("ex-to").value),
  reason: $("ex-reason").value,
});
$("btn-boundary").onclick = () => runAction("/api/runs/" + currentRunId + "/boundaries", {
  cycleNo: Number($("bd-cycle").value),
  boundaryTsMs: Number($("bd-ts").value),
});
$("btn-derive").onclick = async () => {
  try {
    const r = await api("/api/runs/" + currentRunId + "/derive", {
      method: "POST",
      body: JSON.stringify({
        ruleVersionId: Number($("rule-picker").value),
        reason: $("derive-reason").value || "researcher recomputation",
      }),
    });
    $("tick-result").textContent = "派生 v" + r.versionNo + " -> " + r.status + " " + r.reviewFlags;
  } catch (e) {
    alert(e.message);
  }
  await refresh();
};
$("btn-send-sample").onclick = async () => {
  try {
    const req = JSON.parse($("sample-json").value);
    const r = await api("/api/runs/" + currentRunId + "/samples", {
      method: "POST",
      body: JSON.stringify(req),
    });
    $("tick-result").textContent = "上报结果: " + r.kind;
  } catch (e) {
    alert(e.message);
  }
  await refresh();
};

(async function init() {
  await loadCatalog();
  await refresh();
})();
