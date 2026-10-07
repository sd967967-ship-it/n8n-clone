package com.automationstudio.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowDefinition(
    int version,
    String name,
    String description,
    List<Node> nodes,
    List<Edge> edges,
    Map<String, Object> pinData) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Node(
      String id,
      String type,
      String name,
      Position position,
      Map<String, Object> config,
      Map<String, Object> settings) {}

  public record Position(int x, int y) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Edge(String id, String source, String target, String sourceHandle) {}
}
