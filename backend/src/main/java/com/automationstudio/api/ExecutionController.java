package com.automationstudio.api;

import com.automationstudio.execution.ExecutionEventPublisher;
import com.automationstudio.execution.ExecutionService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class ExecutionController {

  private final ExecutionService service;
  private final ExecutionEventPublisher events;

  public ExecutionController(ExecutionService service, ExecutionEventPublisher events) {
    this.service = service;
    this.events = events;
  }

  @PostMapping("/api/workflows/{id}/execute")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Object> execute(
      @PathVariable UUID id, @RequestBody(required = false) Map<String, Object> payload) {
    UUID executionId = service.execute(id, payload);
    return Map.of("executionId", executionId);
  }

  @GetMapping("/api/executions")
  public java.util.List<Map<String, Object>> list(
      @RequestParam(required = false) UUID workflowId) {
    return service.list(workflowId);
  }

  @GetMapping("/api/executions/{id}")
  public Map<String, Object> get(@PathVariable UUID id) {
    return service.get(id);
  }

  @GetMapping("/api/executions/{id}/events")
  public SseEmitter events(
      @PathVariable UUID id,
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
    return events.subscribe(id, lastEventId);
  }

  @PostMapping("/api/executions/{id}/cancel")
  public Map<String, Object> cancel(@PathVariable UUID id) {
    service.cancel(id);
    return Map.of("cancelled", true);
  }
}
