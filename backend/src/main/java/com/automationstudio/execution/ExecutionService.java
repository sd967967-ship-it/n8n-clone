package com.automationstudio.execution;

import com.automationstudio.workflow.WorkflowDefinition;
import com.automationstudio.workflow.WorkflowService;
import com.automationstudio.workflow.WorkflowValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ExecutionService {

  private final WorkflowService workflows;
  private final WorkflowValidator validator;
  private final ExecutionRepository executionRepo;
  private final ExecutionNodeRepository nodeRepo;
  private final ExecutionEngine engine;
  private final ObjectMapper json;

  public ExecutionService(
      WorkflowService workflows,
      WorkflowValidator validator,
      ExecutionRepository executionRepo,
      ExecutionNodeRepository nodeRepo,
      ExecutionEngine engine,
      ObjectMapper json) {
    this.workflows = workflows;
    this.validator = validator;
    this.executionRepo = executionRepo;
    this.nodeRepo = nodeRepo;
    this.engine = engine;
    this.json = json;
  }

  @Transactional
  public UUID execute(UUID workflowId, Map<String, Object> payload) {
    var wf = workflows.get(workflowId);
    WorkflowDefinition def = workflows.read(wf);
    if (!"VALID".equals(validator.validate(def).status())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Workflow is DRAFT, fix issues first");
    }
    ExecutionEntity exec = new ExecutionEntity();
    exec.workflowId = workflowId;
    exec.workflowVersion = wf.version;
    exec.definitionSnapshotJson = wf.definitionJson;
    exec.triggerType = "manual";
    try {
      exec.triggerPayloadJson = json.writeValueAsString(payload == null ? Map.of() : payload);
    } catch (Exception e) {
      exec.triggerPayloadJson = "{}";
    }
    executionRepo.save(exec);
    // dispatch only after the row commits, or the engine thread cannot see it
    org.springframework.transaction.support.TransactionSynchronizationManager
        .registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
              @Override
              public void afterCommit() {
                engine.submit(exec.id);
              }
            });
    return exec.id;
  }

  public List<Map<String, Object>> list(UUID workflowId) {
    List<ExecutionEntity> execs = workflowId == null
        ? executionRepo.findAll().stream()
            .sorted((a, b) -> b.startedAt.compareTo(a.startedAt)).limit(100).toList()
        : executionRepo.findByWorkflowIdOrderByStartedAtDesc(workflowId);
    return execs.stream()
        .map(e -> Map.<String, Object>of(
            "id", e.id, "workflowId", e.workflowId, "status", e.status,
            "startedAt", e.startedAt.toString(),
            "finishedAt", e.finishedAt == null ? "" : e.finishedAt.toString()))
        .toList();
  }

  public Map<String, Object> get(UUID id) {
    ExecutionEntity e = executionRepo.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
    List<Map<String, Object>> nodes = nodeRepo.findByExecutionId(id).stream()
        .map(n -> Map.<String, Object>of(
            "nodeId", n.nodeId, "status", n.status, "attempt", n.attempt,
            "durationMs", n.durationMs, "truncated", n.truncated,
            "error", n.errorMessage == null ? "" : n.errorMessage,
            "input", parse(n.inputJson), "output", parse(n.outputJson)))
        .toList();
    return Map.of(
        "id", e.id, "workflowId", e.workflowId, "status", e.status,
        "result", parse(e.resultJson),
        "error", e.errorMessage == null ? "" : e.errorMessage,
        "nodes", nodes);
  }

  private Object parse(String raw) {
    if (raw == null || raw.isBlank()) return Map.of();
    try {
      return json.readValue(raw, Object.class);
    } catch (Exception ex) {
      return raw;
    }
  }

  public void cancel(UUID id) {
    if (!engine.cancel(id)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Execution not running");
    }
  }
}
