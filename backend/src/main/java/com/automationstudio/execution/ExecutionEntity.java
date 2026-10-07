package com.automationstudio.execution;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "executions")
public class ExecutionEntity {
  @Id
  public UUID id;
  @Column(name = "workflow_id", nullable = false)
  public UUID workflowId;
  @Column(name = "workflow_version", nullable = false)
  public int workflowVersion;
  @Column(name = "definition_snapshot_json", nullable = false, columnDefinition = "jsonb")
  public String definitionSnapshotJson;
  @Column(nullable = false)
  public String status = "QUEUED";
  @Column(name = "trigger_type", nullable = false)
  public String triggerType = "manual";
  @Column(name = "trigger_payload_json", nullable = false, columnDefinition = "jsonb")
  public String triggerPayloadJson = "{}";
  @Column(name = "result_json", columnDefinition = "jsonb")
  public String resultJson;
  @Column(name = "started_at", nullable = false)
  public Instant startedAt;
  @Column(name = "finished_at")
  public Instant finishedAt;
  @Column(name = "error_message")
  public String errorMessage;

  @PrePersist
  public void prePersist() {
    if (id == null) id = UUID.randomUUID();
    if (startedAt == null) startedAt = Instant.now();
  }
}
