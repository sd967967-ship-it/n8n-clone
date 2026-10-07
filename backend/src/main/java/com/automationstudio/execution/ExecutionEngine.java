package com.automationstudio.execution;

import com.automationstudio.security.CredentialService;
import com.automationstudio.workflow.WorkflowDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Async graph scheduler: delivered/dead edges, parallel branches, per-node
 * onError/retries/timeouts, cooperative + interrupt cancellation, crash-safe
 * statuses. Multi-parent input waits for all live parents (n8n-like wait_all).
 */
@Component
public class ExecutionEngine {

  private final ExecutorService pool;
  private final ExecutionRepository executionRepo;
  private final ExecutionNodeRepository nodeRepo;
  private final ExecutionEventPublisher events;
  private final NodeExecutorRegistry executors;
  private final CredentialService credentials;
  private final ObjectMapper json;
  private final long workflowMaxDurationMs;
  private final long nodeDefaultTimeoutMs;
  private final int maxStoredOutputBytes;
  private final Map<UUID, Future<?>> running = new ConcurrentHashMap<>();
  private final Map<UUID, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

  public ExecutionEngine(
      ExecutionRepository executionRepo,
      ExecutionNodeRepository nodeRepo,
      ExecutionEventPublisher events,
      NodeExecutorRegistry executors,
      CredentialService credentials,
      ObjectMapper json,
      @Value("${engine.max-concurrent-executions:8}") int maxConcurrent,
      @Value("${engine.workflow-max-duration-ms:900000}") long workflowMaxDurationMs,
      @Value("${engine.node-default-timeout-ms:60000}") long nodeDefaultTimeoutMs,
      @Value("${engine.max-stored-output-bytes:262144}") int maxStoredOutputBytes) {
    this.pool = Executors.newFixedThreadPool(maxConcurrent);
    this.executionRepo = executionRepo;
    this.nodeRepo = nodeRepo;
    this.events = events;
    this.executors = executors;
    this.credentials = credentials;
    this.json = json;
    this.workflowMaxDurationMs = workflowMaxDurationMs;
    this.nodeDefaultTimeoutMs = nodeDefaultTimeoutMs;
    this.maxStoredOutputBytes = maxStoredOutputBytes;
  }

  public void submit(UUID executionId) {
    AtomicBoolean cancelled = new AtomicBoolean(false);
    cancelFlags.put(executionId, cancelled);
    Future<?> f = pool.submit(() -> run(executionId, cancelled));
    running.put(executionId, f);
  }

  public boolean cancel(UUID executionId) {
    AtomicBoolean flag = cancelFlags.get(executionId);
    if (flag != null) flag.set(true);
    Future<?> f = running.get(executionId);
    if (f != null) f.cancel(true);
    return flag != null;
  }

  private void run(UUID executionId, AtomicBoolean cancelled) {
    long wallStart = System.currentTimeMillis();
    ExecutionEntity exec = executionRepo.findById(executionId).orElse(null);
    if (exec == null) return;
    WorkflowDefinition wf;
    try {
      wf = json.readValue(exec.definitionSnapshotJson, WorkflowDefinition.class);
    } catch (Exception e) {
      fail(exec, "Snapshot corrupt");
      return;
    }
    exec.status = "RUNNING";
    executionRepo.save(exec);
    events.publish(executionId, "execution.started", null, Map.of("executionId", id(executionId)));

    Map<String, WorkflowDefinition.Node> nodes = new LinkedHashMap<>();
    for (var n : wf.nodes()) nodes.put(n.id(), n);
    Map<String, List<WorkflowDefinition.Edge>> incoming = new LinkedHashMap<>();
    Map<String, List<WorkflowDefinition.Edge>> outgoing = new LinkedHashMap<>();
    nodes.keySet().forEach(id -> {
      incoming.put(id, new ArrayList<>());
      outgoing.put(id, new ArrayList<>());
    });
    for (var e : wf.edges()) {
      if (nodes.containsKey(e.source()) && nodes.containsKey(e.target())) {
        outgoing.get(e.source()).add(e);
        incoming.get(e.target()).add(e);
      }
    }
    String triggerId = wf.nodes().stream()
        .filter(n -> n.type() != null && n.type().endsWith("_trigger"))
        .map(WorkflowDefinition.Node::id).findFirst().orElse(null);

    Map<String, Integer> resolved = new LinkedHashMap<>();
    Map<String, Integer> delivered = new LinkedHashMap<>();
    nodes.keySet().forEach(id -> {
      resolved.put(id, 0);
      delivered.put(id, 0);
    });
    Map<String, Map<String, Object>> outputs = new ConcurrentHashMap<>();
    Map<String, String> nodeStatus = new ConcurrentHashMap<>();
    // seed trigger as delivered from a virtual edge
    if (triggerId != null) {
      resolved.put(triggerId, incoming.get(triggerId).size());
      delivered.put(triggerId, 1);
    }

    boolean stopFailed = false;
    String stopError = null;
    try {
      while (true) {
        if (cancelled.get()) {
          cancelExec(exec, nodeStatus, nodes, executionId);
          return;
        }
        if (System.currentTimeMillis() - wallStart > workflowMaxDurationMs) {
          fail(exec, "Workflow wall-clock exceeded");
          return;
        }
        // find runnable: all incoming resolved, >=1 delivered, not yet run
        List<String> runnable = new ArrayList<>();
        for (String id : nodes.keySet()) {
          if (nodeStatus.containsKey(id)) continue;
          if (stopFailed) break;
          int total = incoming.get(id).size();
          if (resolved.get(id) >= total && delivered.get(id) > 0) runnable.add(id);
        }
        if (runnable.isEmpty()) {
          // everything else with all-dead inputs becomes SKIPPED
          boolean progressed = false;
          for (String id : nodes.keySet()) {
            if (nodeStatus.containsKey(id)) continue;
            int total = incoming.get(id).size();
            if (resolved.get(id) >= total && delivered.get(id) == 0 && total > 0) {
              skip(id, exec, nodeStatus, outgoing, resolved, delivered, executionId);
              progressed = true;
            }
          }
          if (!progressed) break;
          continue;
        }
        // run batch in parallel, each with its own timeout
        Map<String, Future<NodeResult>> futures = new LinkedHashMap<>();
        for (String id : runnable) {
          nodeStatus.put(id, "RUNNING");
          persistStart(exec, id);
          events.publish(executionId, "node.started", id, Map.of("nodeId", id));
          WorkflowDefinition.Node node = nodes.get(id);
          Map<String, Object> input = buildInput(id, incoming, outputs);
          Map<String, Map<String, Object>> upstream = buildUpstream(id, incoming, outputs);
          long timeout = nodeTimeout(node);
          Callable<NodeResult> task = () -> {
            long t0 = System.currentTimeMillis();
            int retries = retriesOf(node);
            long delay = delayOf(node);
            Exception lastError = null;
            for (int attempt = 1; attempt <= 1 + retries; attempt++) {
              if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("cancelled");
              }
              updateAttempt(exec, id, attempt);
              try {
                ExecContext ctx = new ExecContext(executionId, configOf(node), input, upstream,
                    name -> credentials.resolve(name), cancelled::get, timeout);
                NodeResult r = executors.get(node.type()).execute(ctx);
                if ("FAILED".equals(r.status())) {
                  lastError = new RuntimeException(r.error());
                  if (attempt <= retries) {
                    Thread.sleep(delay);
                    continue;
                  }
                  return r;
                }
                return r;
              } catch (InterruptedException ie) {
                throw ie;
              } catch (Exception e) {
                lastError = e;
                if (attempt <= retries) {
                  Thread.sleep(delay);
                }
              }
            }
            throw lastError == null ? new RuntimeException("failed") : lastError;
          };
          futures.put(id, pool.submit(task));
        }
        for (var entry : futures.entrySet()) {
          String id = entry.getKey();
          WorkflowDefinition.Node node = nodes.get(id);
          long timeout = nodeTimeout(node);
          NodeResult result;
          try {
            result = entry.getValue().get(timeout + 5000, TimeUnit.MILLISECONDS);
          } catch (java.util.concurrent.TimeoutException e) {
            entry.getValue().cancel(true);
            result = NodeResult.failure("Node timeout after " + timeout + "ms", timeout);
          } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof InterruptedException) {
              cancelExec(exec, nodeStatus, nodes, executionId);
              return;
            }
            result = NodeResult.failure(String.valueOf(cause.getMessage()), 0);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelExec(exec, nodeStatus, nodes, executionId);
            return;
          }
          if (cancelled.get()) {
            cancelExec(exec, nodeStatus, nodes, executionId);
            return;
          }
          handleResult(id, node, result, exec, nodeStatus, outputs, outgoing, resolved,
              delivered, executionId);
          if ("stop".equals(onError(node)) && "FAILED".equals(result.status())) {
            stopFailed = true;
            stopError = result.error();
          }
        }
        if (stopFailed) break;
      }
    } catch (Exception e) {
      fail(exec, String.valueOf(e.getMessage()));
      return;
    }
    // unrun nodes become SKIPPED
    for (String id : nodes.keySet()) {
      if (!nodeStatus.containsKey(id)) {
        skip(id, exec, nodeStatus, outgoing, resolved, delivered, executionId);
      }
    }
    finish(exec, stopFailed, stopError, outputs, executionId);
    running.remove(executionId);
    cancelFlags.remove(executionId);
  }

  // ---- result handling ----

  private void handleResult(
      String id, WorkflowDefinition.Node node, NodeResult result, ExecutionEntity exec,
      Map<String, String> nodeStatus, Map<String, Map<String, Object>> outputs,
      Map<String, List<WorkflowDefinition.Edge>> outgoing, Map<String, Integer> resolved,
      Map<String, Integer> delivered, UUID executionId) {
    String onError = onError(node);
    boolean failed = "FAILED".equals(result.status());
    if (!failed) {
      nodeStatus.put(id, "SUCCESS");
      Map<String, Object> out = result.output() == null ? Map.of() : result.output();
      outputs.put(id, out);
      persistDone(exec, id, "SUCCESS", null, out, result.durationMs());
      events.publish(executionId, "node.succeeded", id, Map.of("nodeId", id));
      if ("condition".equals(node.type())) {
        Object branch = out.get("_branch");
        String want = Boolean.TRUE.equals(branch) ? "true" : "false";
        for (var e : outgoing.get(id)) {
          resolve(e, want.equals(e.sourceHandle()), resolved, delivered);
        }
      } else {
        for (var e : outgoing.get(id)) resolve(e, true, resolved, delivered);
      }
    } else if ("continue".equals(onError)) {
      nodeStatus.put(id, "FAILED");
      Map<String, Object> errOut = Map.of("error", Map.of("message",
          result.error() == null ? "failed" : result.error()));
      outputs.put(id, errOut);
      persistDone(exec, id, "FAILED", result.error(), errOut, result.durationMs());
      events.publish(executionId, "node.failed", id,
          Map.of("nodeId", id, "error", String.valueOf(result.error())));
      for (var e : outgoing.get(id)) resolve(e, true, resolved, delivered);
    } else if ("route".equals(onError)) {
      nodeStatus.put(id, "FAILED");
      Map<String, Object> errOut = Map.of("error", Map.of("message",
          result.error() == null ? "failed" : result.error()));
      outputs.put(id, errOut);
      persistDone(exec, id, "FAILED", result.error(), errOut, result.durationMs());
      events.publish(executionId, "node.failed", id,
          Map.of("nodeId", id, "error", String.valueOf(result.error())));
      for (var e : outgoing.get(id)) {
        resolve(e, "error".equals(e.sourceHandle()), resolved, delivered);
      }
    } else {
      nodeStatus.put(id, "FAILED");
      persistDone(exec, id, "FAILED", result.error(), result.output(), result.durationMs());
      events.publish(executionId, "node.failed", id,
          Map.of("nodeId", id, "error", String.valueOf(result.error())));
      for (var e : outgoing.get(id)) resolve(e, false, resolved, delivered);
    }
  }

  private void resolve(WorkflowDefinition.Edge e, boolean isDelivered,
      Map<String, Integer> resolved, Map<String, Integer> delivered) {
    resolved.merge(e.target(), 1, Integer::sum);
    if (isDelivered) delivered.merge(e.target(), 1, Integer::sum);
  }

  private void skip(String id, ExecutionEntity exec, Map<String, String> nodeStatus,
      Map<String, List<WorkflowDefinition.Edge>> outgoing, Map<String, Integer> resolved,
      Map<String, Integer> delivered, UUID executionId) {
    if (nodeStatus.containsKey(id)) return;
    nodeStatus.put(id, "SKIPPED");
    persistDone(exec, id, "SKIPPED", null, Map.of(), 0);
    events.publish(executionId, "node.skipped", id, Map.of("nodeId", id));
    for (var e : outgoing.getOrDefault(id, List.of())) resolve(e, false, resolved, delivered);
  }

  // ---- inputs ----

  private Map<String, Object> buildInput(String id,
      Map<String, List<WorkflowDefinition.Edge>> incoming,
      Map<String, Map<String, Object>> outputs) {
    List<String> parents = incoming.get(id).stream().map(WorkflowDefinition.Edge::source)
        .filter(outputs::containsKey).toList();
    if (parents.size() == 1) return outputs.get(parents.get(0));
    Map<String, Object> merged = new LinkedHashMap<>();
    for (String p : parents) merged.put(p, outputs.get(p));
    return merged;
  }

  private Map<String, Map<String, Object>> buildUpstream(String id,
      Map<String, List<WorkflowDefinition.Edge>> incoming,
      Map<String, Map<String, Object>> outputs) {
    Map<String, Map<String, Object>> upstream = new LinkedHashMap<>();
    // transitive closure of parents
    List<String> stack = new ArrayList<>(
        incoming.get(id).stream().map(WorkflowDefinition.Edge::source).toList());
    java.util.Set<String> seen = new java.util.HashSet<>();
    while (!stack.isEmpty()) {
      String cur = stack.remove(stack.size() - 1);
      if (!seen.add(cur)) continue;
      if (outputs.containsKey(cur)) upstream.put(cur, outputs.get(cur));
      for (var e : incoming.getOrDefault(cur, List.of())) stack.add(e.source());
    }
    return upstream;
  }

  // ---- settings ----

  private String onError(WorkflowDefinition.Node node) {
    if (node.settings() == null) return "stop";
    Object v = node.settings().get("onError");
    return v == null ? "stop" : v.toString();
  }

  private int retriesOf(WorkflowDefinition.Node node) {
    if (node.settings() == null) return 0;
    try {
      int r = Integer.parseInt(String.valueOf(node.settings().getOrDefault("retries", 0)));
      return Math.min(5, Math.max(0, r));
    } catch (Exception e) {
      return 0;
    }
  }

  private long delayOf(WorkflowDefinition.Node node) {
    if (node.settings() == null) return 1000;
    try {
      return Long.parseLong(String.valueOf(node.settings().getOrDefault("retryDelayMs", 1000)));
    } catch (Exception e) {
      return 1000;
    }
  }

  private long nodeTimeout(WorkflowDefinition.Node node) {
    if (node.settings() != null && node.settings().get("timeoutMs") != null) {
      try {
        return Long.parseLong(String.valueOf(node.settings().get("timeoutMs")));
      } catch (Exception ignored) {
      }
    }
    return nodeDefaultTimeoutMs;
  }

  private Map<String, Object> configOf(WorkflowDefinition.Node node) {
    return node.config() == null ? Map.of() : node.config();
  }

  // ---- persistence helpers ----

  private void persistStart(ExecutionEntity exec, String nodeId) {
    ExecutionNodeEntity row = new ExecutionNodeEntity();
    row.executionId = exec.id;
    row.nodeId = nodeId;
    row.status = "RUNNING";
    row.startedAt = Instant.now();
    nodeRepo.save(row);
  }

  private void updateAttempt(ExecutionEntity exec, String nodeId, int attempt) {
    // attempt recorded on the finished row; in-flight rows keep latest attempt on completion
  }

  private void persistDone(ExecutionEntity exec, String nodeId, String status,
      String error, Map<String, Object> output, long durationMs) {
    List<ExecutionNodeEntity> rows = nodeRepo.findByExecutionId(exec.id).stream()
        .filter(r -> r.nodeId.equals(nodeId) && "RUNNING".equals(r.status)).toList();
    ExecutionNodeEntity row = rows.isEmpty() ? new ExecutionNodeEntity() : rows.get(rows.size() - 1);
    row.executionId = exec.id;
    row.nodeId = nodeId;
    row.status = status;
    row.errorMessage = error;
    row.durationMs = durationMs;
    row.finishedAt = Instant.now();
    String serialized = serialize(output);
    if (serialized.length() > maxStoredOutputBytes) {
      row.outputJson = serialized.substring(0, maxStoredOutputBytes);
      row.truncated = true;
    } else {
      row.outputJson = serialized;
    }
    row.outputSizeBytes = serialized.length();
    row.inputJson = "{}";
    nodeRepo.save(row);
  }

  private String serialize(Object o) {
    try {
      return json.writeValueAsString(o == null ? Map.of() : o);
    } catch (Exception e) {
      return "{}";
    }
  }

  // ---- finish ----

  private void finish(ExecutionEntity exec, boolean stopFailed, String stopError,
      Map<String, Map<String, Object>> outputs, UUID executionId) {
    // result = output node value(s) that ran
    Map<String, Object> result = new LinkedHashMap<>();
    for (var e : outputs.entrySet()) result.put(e.getKey(), e.getValue().get("value"));
    exec.resultJson = serialize(result.isEmpty() ? Map.of() : result);
    exec.finishedAt = Instant.now();
    if (stopFailed) {
      exec.status = "FAILED";
      exec.errorMessage = stopError;
    } else {
      exec.status = "SUCCESS";
    }
    executionRepo.save(exec);
    events.publish(executionId, "execution.finished", null,
        Map.of("status", exec.status, "executionId", id(executionId)));
    events.close(executionId);
  }

  private ExecutionEntity fail(ExecutionEntity exec, String error) {
    exec.status = "FAILED";
    exec.errorMessage = error;
    exec.finishedAt = Instant.now();
    executionRepo.save(exec);
    events.publish(exec.id, "execution.finished", null,
        Map.of("status", "FAILED", "error", error, "executionId", id(exec.id)));
    events.close(exec.id);
    running.remove(exec.id);
    cancelFlags.remove(exec.id);
    return exec;
  }

  private void cancelExec(ExecutionEntity exec, Map<String, String> nodeStatus,
      Map<String, WorkflowDefinition.Node> nodes, UUID executionId) {
    for (var e : nodeStatus.entrySet()) {
      if ("RUNNING".equals(e.getValue())) {
        persistDone(exec, e.getKey(), "CANCELLED", "cancelled", Map.of(), 0);
        events.publish(executionId, "node.skipped", e.getKey(), Map.of("nodeId", e.getKey()));
      }
    }
    for (String id : nodes.keySet()) {
      if (!nodeStatus.containsKey(id)) {
        persistDone(exec, id, "SKIPPED", null, Map.of(), 0);
      }
    }
    exec.status = "CANCELLED";
    exec.finishedAt = Instant.now();
    executionRepo.save(exec);
    events.publish(executionId, "execution.finished", null,
        Map.of("status", "CANCELLED", "executionId", id(executionId)));
    events.close(executionId);
    running.remove(executionId);
    cancelFlags.remove(executionId);
  }

  private static String id(UUID id) {
    return id.toString();
  }
}
