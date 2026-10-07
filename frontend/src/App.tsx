import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  Background,
  Controls,
  MiniMap,
  ReactFlow,
  addEdge,
  useEdgesState,
  useNodesState,
  type Connection,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { useWorkflowStore } from "./store";
import { api } from "./api";
import type { NodeType } from "./types";

const PALETTE: { type: NodeType; label: string }[] = [
  { type: "manual_trigger", label: "Manual Trigger" },
  { type: "http_request", label: "HTTP Request" },
  { type: "llm", label: "LLM" },
  { type: "condition", label: "Condition" },
  { type: "transform", label: "Transform" },
  { type: "output", label: "Output" },
  { type: "app_action", label: "App Action" },
  { type: "app_trigger", label: "App Trigger" },
];

const DEFAULT_CONFIG: Record<NodeType, Record<string, unknown>> = {
  manual_trigger: { payload: {} },
  http_request: { method: "GET", url: "" },
  llm: { prompt: "", outputFormat: "text" },
  condition: { expression: "" },
  transform: { mapping: {} },
  output: { value: "" },
  app_action: { app: "", operation: "", connection: "" },
  app_trigger: { app: "", event: "", connection: "" },
};

export default function App() {
  const s = useWorkflowStore();
  const [nodes, setNodes, onNodesChange] = useNodesState(s.nodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(s.edges);
  const [workflows, setWorkflows] = useState<
    { id: string; name: string; status: string }[]
  >([]);
  const [configText, setConfigText] = useState("{}");
  const [configError, setConfigError] = useState("");
  const [aiPrompt, setAiPrompt] = useState("");
  const [busy, setBusy] = useState("");
  const esRef = useRef<EventSource | null>(null);

  useEffect(() => {
    setNodes(s.nodes);
  }, [s.nodes, setNodes]);
  useEffect(() => {
    setEdges(s.edges);
  }, [s.edges, setEdges]);

  const refreshList = useCallback(async () => {
    try {
      setWorkflows(await api.workflows.list());
    } catch {
      /* backend down */
    }
  }, []);
  useEffect(() => {
    refreshList();
  }, [refreshList]);

  const selected = nodes.find((n) => n.id === s.selectedNodeId) ?? null;
  useEffect(() => {
    setConfigText(
      JSON.stringify((selected?.data as { config?: unknown })?.config ?? {}, null, 2)
    );
    setConfigError("");
  }, [selected?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  const styledNodes = useMemo(
    () =>
      nodes.map((n) =>
        s.nodeStatuses[n.id]
          ? {
              ...n,
              style: {
                border: "2px solid",
                borderColor:
                  s.nodeStatuses[n.id] === "SUCCESS"
                    ? "#22c55e"
                    : s.nodeStatuses[n.id] === "FAILED"
                      ? "#ef4444"
                      : s.nodeStatuses[n.id] === "SKIPPED"
                        ? "#6b7280"
                        : "#3b82f6",
              },
            }
          : n
      ),
    [nodes, s.nodeStatuses]
  );

  const onSelectionChange = useCallback(
    ({ nodes: sel }: { nodes: { id: string }[] }) => {
      const id = sel[0]?.id ?? null;
      if (id !== useWorkflowStore.getState().selectedNodeId) s.selectNode(id);
    },
    [s]
  );

  const onConnect = useCallback(
    (c: Connection) => {
      const next = addEdge({ ...c, id: `e_${Date.now()}` }, edges);
      setEdges(next);
      s.setEdges(next);
    },
    [edges, setEdges, s]
  );

  const toDefinition = useCallback(
    () => ({
      version: 1,
      name: s.name,
      description: "",
      nodes: nodes.map((n) => ({
        id: n.id,
        type: (n.data as { nodeType: string }).nodeType,
        name: (n.data as { label: string }).label ?? n.id,
        position: n.position,
        config: (n.data as { config?: unknown }).config ?? {},
        settings: { onError: "stop", retries: 0, timeoutMs: 60000 },
      })),
      edges: edges.map((e) => ({
        id: e.id,
        source: e.source,
        target: e.target,
        ...(e.sourceHandle ? { sourceHandle: e.sourceHandle } : {}),
      })),
      pinData: {},
    }),
    [nodes, edges, s.name]
  );

  const doSave = useCallback(async () => {
    setBusy("Saving…");
    try {
      const def = toDefinition();
      let savedId = s.id;
      if (s.id && s.version !== null) {
        const r = await api.workflows.update(s.id, s.version, def);
        savedId = s.id;
        s.loadWorkflow(nodes, edges, {
          id: s.id,
          name: s.name,
          version: r.version,
          status: r.status,
          issues: s.issues,
        });
      } else {
        const r = await api.workflows.create(def);
        savedId = r.id;
        s.loadWorkflow(nodes, edges, {
          id: r.id,
          name: s.name,
          version: r.version,
          status: r.status,
          issues: s.issues,
        });
      }
      if (!savedId) throw new Error("save did not return an id");
      const v = await api.workflows.validate(savedId);
      useWorkflowStore.setState({ issues: v.errors ?? [], status: v.status });
      refreshList();
    } catch (e) {
      s.appendLog(`save failed: ${(e as Error).message}`);
    } finally {
      setBusy("");
    }
  }, [s, nodes, edges, toDefinition, refreshList]);

  const doRun = useCallback(async () => {
    if (!s.id) {
      s.appendLog("save the workflow first");
      return;
    }
    setBusy("Running…");
    try {
      const { executionId } = await api.workflows.execute(s.id, {});
      s.setExecution({ executionId, nodeStatuses: {}, logs: [], result: null });
      esRef.current?.close();
      const es = new EventSource(`/api/executions/${executionId}/events`);
      esRef.current = es;
      es.addEventListener("node.started", (ev) => {
        const d = JSON.parse((ev as MessageEvent).data);
        s.setNodeStatus(d.nodeId, "RUNNING");
        s.appendLog(`▶ ${d.nodeId}`);
      });
      es.addEventListener("node.succeeded", (ev) => {
        const d = JSON.parse((ev as MessageEvent).data);
        s.setNodeStatus(d.nodeId, "SUCCESS");
        s.appendLog(`✓ ${d.nodeId}`);
      });
      es.addEventListener("node.failed", (ev) => {
        const d = JSON.parse((ev as MessageEvent).data);
        s.setNodeStatus(d.nodeId, "FAILED");
        s.appendLog(`✗ ${d.nodeId}: ${d.error ?? ""}`);
      });
      es.addEventListener("node.skipped", (ev) => {
        const d = JSON.parse((ev as MessageEvent).data);
        s.setNodeStatus(d.nodeId, "SKIPPED");
        s.appendLog(`– ${d.nodeId} skipped`);
      });
      es.addEventListener("execution.finished", async (ev) => {
        const d = JSON.parse((ev as MessageEvent).data);
        s.appendLog(`finished: ${d.status}`);
        const full = await api.executions.get(executionId);
        s.setExecution({ result: full.result });
        es.close();
        setBusy("");
      });
      es.onerror = () => {
        s.appendLog("event stream error");
        es.close();
        setBusy("");
      };
    } catch (e) {
      s.appendLog(`run failed: ${(e as Error).message}`);
      setBusy("");
    }
  }, [s]);

  const doTestNode = useCallback(async () => {
    if (!selected) return;
    setBusy("Testing…");
    try {
      const r = await api.nodes.test(
        {
          id: selected.id,
          type: (selected.data as { nodeType: string }).nodeType,
          config: (selected.data as { config?: unknown }).config ?? {},
        },
        s.id,
        undefined
      );
      s.appendLog(`test ${selected.id}: ${r.status} ${JSON.stringify(r.output).slice(0, 300)}`);
    } catch (e) {
      s.appendLog(`test failed: ${(e as Error).message}`);
    } finally {
      setBusy("");
    }
  }, [s, selected]);

  const doNew = useCallback(() => {
    s.loadWorkflow([], [], {
      id: null,
      name: "Untitled",
      version: null,
      status: null,
      issues: [],
    });
  }, [s]);

  const doOpen = useCallback(
    async (id: string) => {
      const w = await api.workflows.get(id);
      const def = w.definition;
      s.loadWorkflow(
        def.nodes.map((n: { id: string; name: string; type: NodeType; position: { x: number; y: number }; config: Record<string, unknown> }) => ({
          id: n.id,
          type: "default",
          position: n.position,
          data: { label: n.name, nodeType: n.type, config: n.config ?? {} },
        })),
        def.edges.map((e: { id: string; source: string; target: string; sourceHandle?: string }) => ({
          id: e.id,
          source: e.source,
          target: e.target,
          ...(e.sourceHandle ? { sourceHandle: e.sourceHandle } : {}),
        })),
        { id: w.id, name: w.name, version: w.version, status: w.status, issues: [] }
      );
    },
    [s]
  );

  const doExport = useCallback(() => {
    const blob = new Blob([JSON.stringify(toDefinition(), null, 2)], {
      type: "application/json",
    });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `${s.name}.json`;
    a.click();
  }, [toDefinition, s.name]);

  const doImport = useCallback(
    async (file: File) => {
      const def = JSON.parse(await file.text());
      s.loadWorkflow(
        def.nodes.map((n: { id: string; name: string; type: NodeType; position: { x: number; y: number }; config: Record<string, unknown> }) => ({
          id: n.id,
          type: "default",
          position: n.position,
          data: { label: n.name, nodeType: n.type, config: n.config ?? {} },
        })),
        def.edges.map((e: { id: string; source: string; target: string; sourceHandle?: string }) => ({
          id: e.id,
          source: e.source,
          target: e.target,
          ...(e.sourceHandle ? { sourceHandle: e.sourceHandle } : {}),
        })),
        { id: null, name: def.name ?? "Imported", version: null, status: null, issues: [] }
      );
    },
    [s]
  );

  const doGenerate = useCallback(async () => {
    if (!aiPrompt.trim()) return;
    setBusy("Generating…");
    try {
      const r = await api.ai.generate(aiPrompt);
      await doOpen(r.id);
      s.appendLog(`generated via ${(r.generatedBy ?? []).join(", ")}: ${r.status}`);
      if (r.warning) s.appendLog(r.warning);
    } catch (e) {
      s.appendLog(`generate failed: ${(e as Error).message}`);
    } finally {
      setBusy("");
    }
  }, [aiPrompt, doOpen, s]);

  const applyConfig = useCallback(() => {
    if (!selected) return;
    try {
      const cfg = JSON.parse(configText);
      s.updateNodeData(selected.id, { config: cfg });
      setConfigError("");
    } catch {
      setConfigError("invalid JSON");
    }
  }, [selected, configText, s]);

  return (
    <div className="flex h-screen flex-col bg-[#0a0a0f] text-gray-200">
      <header className="flex flex-wrap items-center gap-2 border-b border-gray-800 px-4 py-2">
        <h1 className="text-lg font-semibold">Automation Studio</h1>
        <input
          aria-label="Workflow name"
          value={s.name}
          onChange={(e) => useWorkflowStore.setState({ name: e.target.value })}
          className="rounded bg-gray-900 px-2 py-1 text-sm"
        />
        <span
          data-testid="workflow-status"
          className={`rounded px-2 py-0.5 text-xs ${
            s.status === "VALID" ? "bg-green-900" : "bg-yellow-900"
          }`}
        >
          {s.status ?? "unsaved"}
        </span>
        <select
          aria-label="Open workflow"
          value={s.id ?? ""}
          onChange={(e) => e.target.value && doOpen(e.target.value)}
          className="rounded bg-gray-900 px-2 py-1 text-sm"
        >
          <option value="">Open…</option>
          {workflows.map((w) => (
            <option key={w.id} value={w.id}>
              {w.name} [{w.status}]
            </option>
          ))}
        </select>
        <div className="ml-auto flex gap-2">
          <button type="button" onClick={doNew} className="rounded bg-gray-800 px-3 py-1 text-sm hover:bg-gray-700">
            New
          </button>
          <button type="button" onClick={doSave} className="rounded bg-gray-800 px-3 py-1 text-sm hover:bg-gray-700">
            Save
          </button>
          <button type="button" onClick={doTestNode} disabled={!selected} className="rounded bg-gray-800 px-3 py-1 text-sm hover:bg-gray-700 disabled:opacity-50">
            Test Node
          </button>
          <button type="button" onClick={doRun} className="rounded bg-indigo-600 px-3 py-1 text-sm hover:bg-indigo-500">
            Run Workflow
          </button>
          <button type="button" onClick={doExport} className="rounded bg-gray-800 px-3 py-1 text-sm hover:bg-gray-700">
            Export
          </button>
          <label className="cursor-pointer rounded bg-gray-800 px-3 py-1 text-sm hover:bg-gray-700">
            Import
            <input
              type="file"
              accept="application/json"
              className="hidden"
              onChange={(e) => e.target.files?.[0] && doImport(e.target.files[0])}
            />
          </label>
        </div>
      </header>
      <div className="flex items-center gap-2 border-b border-gray-800 px-4 py-1.5">
        <input
          aria-label="AI builder prompt"
          placeholder="Describe a workflow… (AI builder)"
          value={aiPrompt}
          onChange={(e) => setAiPrompt(e.target.value)}
          className="flex-1 rounded bg-gray-900 px-2 py-1 text-sm"
        />
        <button type="button" onClick={doGenerate} className="rounded bg-purple-700 px-3 py-1 text-sm hover:bg-purple-600">
          Generate
        </button>
        {busy && <span className="text-xs text-gray-400">{busy}</span>}
      </div>
      {s.issues.length > 0 && (
        <div data-testid="validation-issues" className="max-h-20 overflow-auto border-b border-yellow-900 bg-yellow-950/30 px-4 py-1 text-xs text-yellow-200">
          {s.issues.map((i, k) => (
            <div key={k}>
              {i.code} {i.nodeId ? `(${i.nodeId})` : ""}: {i.message}
            </div>
          ))}
        </div>
      )}
      <div className="flex min-h-0 flex-1">
        <aside aria-label="Node palette" className="w-52 shrink-0 border-r border-gray-800 p-2">
          <h2 className="mb-2 text-xs uppercase tracking-wide text-gray-500">Nodes</h2>
          <ul className="space-y-1">
            {PALETTE.map((p) => (
              <li key={p.type}>
                <button
                  type="button"
                  data-testid={`palette-${p.type}`}
                  onClick={() => {
                    const id = s.addNode(p.type, p.label);
                    s.updateNodeData(id, { config: DEFAULT_CONFIG[p.type] });
                  }}
                  className="w-full rounded bg-gray-900 px-2 py-1.5 text-left text-sm hover:bg-gray-800"
                >
                  {p.label}
                </button>
              </li>
            ))}
          </ul>
          <h2 className="mb-1 mt-3 text-xs uppercase tracking-wide text-gray-500">Defaults</h2>
          <pre className="whitespace-pre-wrap text-[11px] text-gray-500">
            {JSON.stringify(DEFAULT_CONFIG.http_request)}
          </pre>
        </aside>
        <main className="flex min-w-0 flex-1 flex-col">
          <div className="min-h-0 flex-1">
            <ReactFlow
              nodes={styledNodes}
              edges={edges}
              onNodesChange={onNodesChange}
              onEdgesChange={(c) => {
                onEdgesChange(c);
              }}
              onConnect={onConnect}
              onNodesDelete={(del) => {
                s.setNodes(nodes.filter((n) => !del.some((d) => d.id === n.id)));
              }}
              onSelectionChange={onSelectionChange}
              onEdgesDelete={(del) => {
                const next = edges.filter((e) => !del.some((d) => d.id === e.id));
                setEdges(next);
                s.setEdges(next);
              }}
              data-testid="canvas"
              colorMode="dark"
              fitView
            >
              <Background />
              <Controls />
              <MiniMap />
            </ReactFlow>
          </div>
          <div data-testid="execution-log" className="h-32 shrink-0 overflow-auto border-t border-gray-800 bg-black/40 p-2 font-mono text-xs">
            {s.logs.map((l, k) => (
              <div key={k}>{l}</div>
            ))}
            {s.result !== null && s.result !== undefined && (
              <div className="text-green-300">result: {JSON.stringify(s.result)}</div>
            )}
          </div>
        </main>
        <aside aria-label="Inspector" className="w-72 shrink-0 border-l border-gray-800 p-3">
          <h2 className="mb-2 text-xs uppercase tracking-wide text-gray-500">Inspector</h2>
          <p data-testid="node-count" className="mb-2 text-sm text-gray-400">
            {nodes.length} node(s) on canvas
          </p>
          {selected ? (
            <div>
              <p className="mb-1 text-sm font-medium">{String(selected.data.label)}</p>
              <p className="mb-2 text-xs text-gray-500">
                {String((selected.data as { nodeType: string }).nodeType)} · {selected.id} ·
                {s.nodeStatuses[selected.id] ? ` ${s.nodeStatuses[selected.id]}` : " not run"}
              </p>
              <label className="mb-1 block text-xs text-gray-400">Config (JSON)</label>
              <textarea
                aria-label="Node config"
                value={configText}
                onChange={(e) => setConfigText(e.target.value)}
                rows={12}
                className="w-full rounded bg-gray-900 p-2 font-mono text-xs"
              />
              {configError && <p className="text-xs text-red-400">{configError}</p>}
              <button
                type="button"
                onClick={applyConfig}
                className="mt-2 rounded bg-gray-800 px-3 py-1 text-sm hover:bg-gray-700"
              >
                Apply
              </button>
            </div>
          ) : (
            <p className="text-sm text-gray-500">Select a node to configure it.</p>
          )}
        </aside>
      </div>
    </div>
  );
}
