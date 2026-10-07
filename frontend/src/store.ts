import { create } from "zustand";
import type { Edge, Node } from "@xyflow/react";
import type { WorkflowNodeData } from "./types";

interface WorkflowState {
  nodes: Node<WorkflowNodeData>[];
  edges: Edge[];
  selectedNodeId: string | null;
  addNode: (nodeType: WorkflowNodeData["nodeType"], label: string) => void;
  selectNode: (id: string | null) => void;
}

let counter = 1;

export const useWorkflowStore = create<WorkflowState>((set) => ({
  nodes: [],
  edges: [],
  selectedNodeId: null,
  addNode: (nodeType, label) =>
    set((s) => ({
      nodes: [
        ...s.nodes,
        {
          id: `node_${counter++}`,
          type: "default",
          position: { x: 100 + s.nodes.length * 40, y: 100 + s.nodes.length * 40 },
          data: { label, nodeType },
        },
      ],
    })),
  selectNode: (id) => set({ selectedNodeId: id }),
}));
