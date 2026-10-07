package com.automationstudio.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkflowService {

  private final WorkflowRepository repository;
  private final WorkflowValidator validator;
  private final ObjectMapper json;

  public WorkflowService(
      WorkflowRepository repository, WorkflowValidator validator, ObjectMapper json) {
    this.repository = repository;
    this.validator = validator;
    this.json = json;
  }

  public List<WorkflowEntity> list() {
    return repository.findAll();
  }

  public WorkflowEntity get(UUID id) {
    return repository
        .findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow not found"));
  }

  @Transactional
  public WorkflowEntity create(WorkflowDefinition def) {
    WorkflowEntity e = new WorkflowEntity();
    e.name = def.name() == null ? "Untitled" : def.name();
    e.description = def.description() == null ? "" : def.description();
    e.definitionJson = write(def);
    e.status = validator.validate(def).status();
    return repository.save(e);
  }

  @Transactional
  public WorkflowEntity update(UUID id, WorkflowDefinition def, int version) {
    WorkflowEntity e = get(id);
    if (e.version != version) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Version mismatch");
    }
    e.name = def.name() == null ? e.name : def.name();
    e.description = def.description() == null ? "" : def.description();
    e.definitionJson = write(def);
    e.status = validator.validate(def).status();
    return repository.save(e);
  }

  @Transactional
  public void delete(UUID id) {
    repository.delete(get(id));
  }

  public WorkflowValidator.ValidationResult validate(UUID id) {
    return validator.validate(read(get(id)));
  }

  public WorkflowDefinition read(WorkflowEntity e) {
    try {
      return json.readValue(e.definitionJson, WorkflowDefinition.class);
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Stored definition is corrupt");
    }
  }

  private String write(WorkflowDefinition def) {
    try {
      return json.writeValueAsString(def);
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bad workflow JSON");
    }
  }
}
