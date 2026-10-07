package com.automationstudio.execution;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "execution_nodes")
public class ExecutionNodeEntity {
  @Id
  public UUID id;
  @Column(name = "execution_id", nullable = false)
  public UUID executionId;
  @Column(name = "node_id", nullable = false)
  public String nodeId;
  @Column(nullable = false)
  public int attempt = 1;
  @Column(nullable = false)
  public String status = "WAITING";
  @Column(name = "input_json", columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  public String inputJson;
  @Column(name = "output_json", columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  public String outputJson;
  @Column(name = "output_size_bytes", nullable = false)
  public int outputSizeBytes;
  @Column(nullable = false)
  public boolean truncated;
  @Column(name = "error_message")
  public String errorMessage;
  @Column(name = "duration_ms", nullable = false)
  public long durationMs;
  @Column(name = "started_at")
  public Instant startedAt;
  @Column(name = "finished_at")
  public Instant finishedAt;

  @PrePersist
  public void prePersist() {
    if (id == null) id = UUID.randomUUID();
  }
}
