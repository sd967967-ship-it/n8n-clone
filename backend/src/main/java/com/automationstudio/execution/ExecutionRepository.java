package com.automationstudio.execution;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionRepository extends JpaRepository<ExecutionEntity, UUID> {
  List<ExecutionEntity> findByWorkflowIdOrderByStartedAtDesc(UUID workflowId);

  List<ExecutionEntity> findByStatusIn(List<String> statuses);
}
