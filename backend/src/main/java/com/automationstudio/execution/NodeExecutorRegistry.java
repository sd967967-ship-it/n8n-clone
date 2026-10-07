package com.automationstudio.execution;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class NodeExecutorRegistry {
  private final Map<String, NodeExecutor> byType;

  public NodeExecutorRegistry(List<NodeExecutor> executors) {
    this.byType =
        executors.stream().collect(Collectors.toMap(NodeExecutor::type, Function.identity()));
  }

  public NodeExecutor get(String type) {
    NodeExecutor e = byType.get(type);
    if (e == null) throw new IllegalArgumentException("No executor for node type: " + type);
    return e;
  }
}
