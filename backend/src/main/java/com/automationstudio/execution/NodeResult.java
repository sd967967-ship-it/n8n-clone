package com.automationstudio.execution;

import java.util.Map;

public record NodeResult(String status, Map<String, Object> output, String error, long durationMs) {
  public static NodeResult success(Map<String, Object> output, long durationMs) {
    return new NodeResult("SUCCESS", output, null, durationMs);
  }

  public static NodeResult failure(String error, long durationMs) {
    return new NodeResult("FAILED", Map.of("error", Map.of("message", error)), error, durationMs);
  }
}
