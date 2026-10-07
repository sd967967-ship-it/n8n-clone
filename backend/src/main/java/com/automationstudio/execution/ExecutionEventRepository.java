package com.automationstudio.execution;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionEventRepository extends JpaRepository<ExecutionEventEntity, UUID> {
  List<ExecutionEventEntity> findByExecutionIdOrderBySeqAsc(UUID executionId);

  List<ExecutionEventEntity> findByExecutionIdAndSeqGreaterThanOrderBySeqAsc(UUID executionId, int seq);
}
