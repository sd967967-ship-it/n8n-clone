package com.automationstudio.execution;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "execution_events",
    uniqueConstraints = @UniqueConstraint(columnNames = {"execution_id", "seq"}))
public class ExecutionEventEntity {
  @Id
  public UUID id;
  @Column(name = "execution_id", nullable = false)
  public UUID executionId;
  @Column(nullable = false)
  public int seq;
  @Column(nullable = false)
  public String type;
  @Column(name = "node_id")
  public String nodeId;
  @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
  public String payloadJson = "{}";
  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  @PrePersist
  public void prePersist() {
    if (id == null) id = UUID.randomUUID();
    if (createdAt == null) createdAt = Instant.now();
  }
}
