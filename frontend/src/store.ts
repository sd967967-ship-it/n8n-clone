import { create } from "zustand";
import type { Edge, Node } from "@xyflow/react";
import type { ValidationIssue, WorkflowNodeData } from "./types";

export interface WorkflowMeta {
  id: string | null;
  name: string;
  version: number | null;
  status: "DRAFT" | "VALID" | null;
  issues: ValidationIssue[];
}

interface WorkflowState extends WorkflowMeta {
  nodes: Node<WorkflowNodeData>[];
  edges: Edge[];
  selectedNodeId: string | null;
  executionId: string | null;
  nodeStatuses: Record<string, string>;
  logs: string[];
  result: unknown;
  addNode: (nodeType: WorkflowNodeData["nodeType"], label: string) => string;
  selectNode: (id: string | null) => void;
  setNodes: (nodes: Node<WorkflowNodeData>[]) => void;
  setEdges: (edges: Edge[]) => void;
  updateNodeData: (id: string, data: Partial<WorkflowNodeData>) => void;
  loadWorkflow: (
    nodes: Node<WorkflowNodeData>[],
    edges: Edge[],
    meta: WorkflowMeta
  ) => void;
  setExecution: (patch: {
    executionId?: string | null;
    nodeStatuses?: Record<string, string>;
    logs?: string[];
    result?: unknown;
  }) => void;
  appendLog: (line: string) => void;
  setNodeStatus: (id: string, status: string) => void;
}

let counter = 1;

export const useWorkflowStore = create<WorkflowState>((set) => ({
  nodes: [],
  edges: [],
  selectedNodeId: null,
  id: null,
  name: "Untitled",
  version: null,
  status: null,
  issues: [],
  executionId: null,
  nodeStatuses: {},
  logs: [],
  result: null,
  addNode: (nodeType, label) => {
    const id = `node_${counter++}`;
    set((s) => ({
      nodes: [
        ...s.nodes,
        {
          id,
          type: "default",
          position: { x: 100 + s.nodes.length * 40, y: 100 + s.nodes.length * 40 },
          data: { label, nodeType, config: {} },
        },
      ],
    }));
    return id;
  },
  selectNode: (id) => set({ selectedNodeId: id }),
  setNodes: (nodes) => set({ nodes }),
  setEdges: (edges) => set({ edges }),
  updateNodeData: (id, data) =>
    set((s) => ({
      nodes: s.nodes.map((n) =>
        n.id === id ? { ...n, data: { ...n.data, ...data } } : n
      ),
    })),
  loadWorkflow: (nodes, edges, meta) =>
    set({
      nodes,
      edges,
      id: meta.id,
      name: meta.name,
      version: meta.version,
      status: meta.status,
      issues: meta.issues,
      nodeStatuses: {},
      logs: [],
      result: null,
      executionId: null,
    }),
  setExecution: (patch) => set(patch),
  appendLog: (line) => set((s) => ({ logs: [...s.logs.slice(-500), line] })),
  setNodeStatus: (id, status) =>
    set((s) => ({ nodeStatuses: { ...s.nodeStatuses, [id]: status } })),
}));
