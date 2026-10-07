export type NodeType =
  | "manual_trigger"
  | "http_request"
  | "llm"
  | "condition"
  | "transform"
  | "output"
  | "app_action"
  | "app_trigger";

export interface WorkflowNodeData {
  label: string;
  nodeType: NodeType;
  [key: string]: unknown;
}

export interface ValidationIssue {
  code: string;
  nodeId?: string;
  path?: string;
  message: string;
}
