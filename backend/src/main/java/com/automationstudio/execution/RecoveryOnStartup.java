package com.automationstudio.execution;

import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Crash recovery: executions left RUNNING/QUEUED become FAILED on startup. */
@Component
public class RecoveryOnStartup implements ApplicationRunner {

  private final ExecutionRepository executionRepo;

  public RecoveryOnStartup(ExecutionRepository executionRepo) {
    this.executionRepo = executionRepo;
  }

  @Override
  public void run(ApplicationArguments args) {
    List<ExecutionEntity> orphans =
        executionRepo.findByStatusIn(List.of("RUNNING", "QUEUED"));
    for (ExecutionEntity e : orphans) {
      e.status = "FAILED";
      e.errorMessage = "Server restarted during execution";
      e.finishedAt = java.time.Instant.now();
      executionRepo.save(e);
    }
  }
}
