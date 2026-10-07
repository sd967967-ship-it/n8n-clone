package com.automationstudio.api;

import com.automationstudio.ai.LlmRouter;
import com.automationstudio.ai.WorkflowGenerationService;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class AiController {

  private final WorkflowGenerationService generation;
  private final LlmRouter router;

  public AiController(WorkflowGenerationService generation, LlmRouter router) {
    this.generation = generation;
    this.router = router;
  }

  @PostMapping("/api/ai/generate-workflow")
  public Map<String, Object> generate(@RequestBody Map<String, Object> body) {
    return generation.generate(String.valueOf(body.getOrDefault("prompt", "")));
  }

  @GetMapping("/api/llm/status")
  public Map<String, Object> llmStatus() {
    return Map.of("tiers", router.status());
  }
}
