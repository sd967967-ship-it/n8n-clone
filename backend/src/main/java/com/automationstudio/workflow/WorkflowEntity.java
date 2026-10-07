package com.automationstudio.workflow;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "workflows")
public class WorkflowEntity {
  @Id
  public UUID id;
  @Column(nullable = false)
  public String name = "";
  @Column(nullable = false)
  public String description = "";
  @Column(name = "definition_json", nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  public String definitionJson = "{}";
  @Column(nullable = false)
  public String status = "DRAFT";
  @Version
  public int version;
  @Column(nullable = false)
  public boolean active = true;
  @Column(name = "created_at", nullable = false)
  public Instant createdAt;
  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;

  @PrePersist
  public void prePersist() {
    if (id == null) id = UUID.randomUUID();
    createdAt = Instant.now();
    updatedAt = Instant.now();
  }

  @PreUpdate
  public void preUpdate() {
    updatedAt = Instant.now();
  }
}
