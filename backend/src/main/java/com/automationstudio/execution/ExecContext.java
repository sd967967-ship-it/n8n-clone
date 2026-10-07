package com.automationstudio.execution;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * What an executor receives. Output of every upstream node is visible;
 * refs resolve against these outputs (+ input alias + execution metadata).
 */
public record ExecContext(
    UUID executionId,
    Map<String, Object> config,
    Map<String, Object> input,
    Map<String, Map<String, Object>> outputs,
    Function<String, String> secret,
    Supplier<Boolean> cancelled,
    long timeoutMs) {

  /** Ref context: node outputs, input alias, execution metadata. */
  public Map<String, Object> refContext() {
    Map<String, Object> ctx = new java.util.HashMap<>();
    outputs.forEach((k, v) -> ctx.put(k, Map.of("output", v)));
    ctx.put("input", input);
    ctx.put("execution",
        Map.of("id", executionId.toString(), "startedAt", java.time.Instant.now().toString()));
    return ctx;
  }
}
