const BASE = "";

async function req(path: string, init?: RequestInit) {
  const res = await fetch(BASE + path, {
    ...init,
    headers: { "Content-Type": "application/json", ...(init?.headers ?? {}) },
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`${res.status} ${text}`);
  }
  if (res.status === 204) return null;
  return res.json();
}

export const api = {
  workflows: {
    list: () => req("/api/workflows"),
    get: (id: string) => req(`/api/workflows/${id}`),
    create: (definition: unknown) =>
      req("/api/workflows", { method: "POST", body: JSON.stringify(definition) }),
    update: (id: string, version: number, definition: unknown) =>
      req(`/api/workflows/${id}`, {
        method: "PUT",
        body: JSON.stringify({ version, definition }),
      }),
    remove: (id: string) => req(`/api/workflows/${id}`, { method: "DELETE" }),
    validate: (id: string) => req(`/api/workflows/${id}/validate`, { method: "POST" }),
    execute: (id: string, payload?: unknown) =>
      req(`/api/workflows/${id}/execute`, {
        method: "POST",
        body: JSON.stringify(payload ?? {}),
      }),
  },
  executions: {
    list: (workflowId?: string) =>
      req(`/api/executions${workflowId ? `?workflowId=${workflowId}` : ""}`),
    get: (id: string) => req(`/api/executions/${id}`),
    cancel: (id: string) => req(`/api/executions/${id}/cancel`, { method: "POST" }),
  },
  nodes: {
    test: (node: unknown, workflowId?: string | null, input?: unknown) =>
      req("/api/nodes/test", {
        method: "POST",
        body: JSON.stringify({ node, workflowId, input }),
      }),
  },
  ai: {
    generate: (prompt: string) =>
      req("/api/ai/generate-workflow", {
        method: "POST",
        body: JSON.stringify({ prompt }),
      }),
  },
  credentials: {
    list: () => req("/api/credentials"),
    create: (name: string, value: string, provider = "custom", authType = "generic", app = "") =>
      req("/api/credentials", {
        method: "POST",
        body: JSON.stringify({ name, value, provider, authType, app }),
      }),
    remove: (id: string) => req(`/api/credentials/${id}`, { method: "DELETE" }),
  },
  llm: {
    status: () => req("/api/llm/status"),
  },
};
