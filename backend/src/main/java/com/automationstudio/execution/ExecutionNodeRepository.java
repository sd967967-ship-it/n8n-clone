package com.automationstudio.execution;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ExecutionNodeRepository extends JpaRepository<ExecutionNodeEntity, UUID> {
  List<ExecutionNodeEntity> findByExecutionId(UUID executionId);

  @Query("select n from ExecutionNodeEntity n where n.executionId in "
      + "(select e.id from ExecutionEntity e where e.workflowId = :workflowId and e.status = 'SUCCESS') "
      + "and n.nodeId = :nodeId and n.status = 'SUCCESS' order by n.finishedAt desc")
  List<ExecutionNodeEntity> findLatestSuccessful(UUID workflowId, String nodeId);
}
