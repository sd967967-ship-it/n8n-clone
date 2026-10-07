package com.automationstudio.api;

import com.automationstudio.workflow.WorkflowDefinition;
import com.automationstudio.workflow.WorkflowEntity;
import com.automationstudio.workflow.WorkflowService;
import com.automationstudio.workflow.WorkflowValidator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

  private final WorkflowService service;

  public WorkflowController(WorkflowService service) {
    this.service = service;
  }

  @PostMapping
  public Map<String, Object> create(@RequestBody WorkflowDefinition def) {
    WorkflowEntity e = service.create(def);
    return Map.of("id", e.id, "version", e.version, "status", e.status);
  }

  @GetMapping
  public List<Map<String, Object>> list() {
    return service.list().stream()
        .map(e -> Map.<String, Object>of(
            "id", e.id, "name", e.name, "status", e.status,
            "version", e.version, "updatedAt", e.updatedAt.toString()))
        .toList();
  }

  @GetMapping("/{id}")
  public Map<String, Object> get(@PathVariable UUID id) {
    WorkflowEntity e = service.get(id);
    return Map.of(
        "id", e.id, "name", e.name, "description", e.description,
        "definition", service.read(e), "status", e.status, "version", e.version);
  }

  @PutMapping("/{id}")
  public Map<String, Object> update(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
    int version = (int) body.get("version");
    WorkflowDefinition def;
    try {
      def = new com.fasterxml.jackson.databind.ObjectMapper()
          .convertValue(body.get("definition"), WorkflowDefinition.class);
    } catch (IllegalArgumentException ex) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "Bad workflow JSON");
    }
    WorkflowEntity e = service.update(id, def, version);
    return Map.of("id", e.id, "version", e.version, "status", e.status);
  }

  @DeleteMapping("/{id}")
  public void delete(@PathVariable UUID id) {
    service.delete(id);
  }

  @PostMapping("/{id}/validate")
  public WorkflowValidator.ValidationResult validate(@PathVariable UUID id) {
    return service.validate(id);
  }
}
