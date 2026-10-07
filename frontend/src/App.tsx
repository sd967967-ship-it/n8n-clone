import { useCallback, useEffect } from "react";
import {
  Background,
  Controls,
  MiniMap,
  ReactFlow,
  useEdgesState,
  useNodesState,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { useWorkflowStore } from "./store";
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

export default function App() {
  const storeNodes = useWorkflowStore((s) => s.nodes);
  const storeEdges = useWorkflowStore((s) => s.edges);
  const addNode = useWorkflowStore((s) => s.addNode);
  const [nodes, setNodes, onNodesChange] = useNodesState(storeNodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(storeEdges);

  useEffect(() => {
    setNodes(storeNodes);
  }, [storeNodes, setNodes]);
  useEffect(() => {
    setEdges(storeEdges);
  }, [storeEdges, setEdges]);

  const onAdd = useCallback(
    (t: NodeType, label: string) => addNode(t, label),
    [addNode]
  );

  return (
    <div className="flex h-screen flex-col bg-[#0a0a0f] text-gray-200">
      <header className="flex items-center gap-2 border-b border-gray-800 px-4 py-2">
        <h1 className="text-lg font-semibold">Automation Studio</h1>
        <div className="ml-auto flex gap-2">
          <button
            type="button"
            className="rounded bg-gray-800 px-3 py-1 text-sm hover:bg-gray-700"
          >
            Test Node
          </button>
          <button
            type="button"
            className="rounded bg-indigo-600 px-3 py-1 text-sm hover:bg-indigo-500"
          >
            Run Workflow
          </button>
          <button
            type="button"
            className="rounded bg-gray-800 px-3 py-1 text-sm hover:bg-gray-700"
          >
            Save
          </button>
        </div>
      </header>
      <div className="flex min-h-0 flex-1">
        <aside
          aria-label="Node palette"
          className="w-52 shrink-0 border-r border-gray-800 p-2"
        >
          <h2 className="mb-2 text-xs uppercase tracking-wide text-gray-500">
            Nodes
          </h2>
          <ul className="space-y-1">
            {PALETTE.map((p) => (
              <li key={p.type}>
                <button
                  type="button"
                  data-testid={`palette-${p.type}`}
                  onClick={() => onAdd(p.type, p.label)}
                  className="w-full rounded bg-gray-900 px-2 py-1.5 text-left text-sm hover:bg-gray-800"
                >
                  {p.label}
                </button>
              </li>
            ))}
          </ul>
        </aside>
        <main className="min-w-0 flex-1">
          <ReactFlow
            nodes={nodes}
            edges={edges}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            data-testid="canvas"
            colorMode="dark"
            fitView
          >
            <Background />
            <Controls />
            <MiniMap />
          </ReactFlow>
        </main>
        <aside
          aria-label="Inspector"
          className="w-64 shrink-0 border-l border-gray-800 p-3"
        >
          <h2 className="mb-2 text-xs uppercase tracking-wide text-gray-500">
            Inspector
          </h2>
          <p data-testid="node-count" className="text-sm text-gray-400">
            {storeNodes.length} node(s) on canvas
          </p>
        </aside>
      </div>
    </div>
  );
}
