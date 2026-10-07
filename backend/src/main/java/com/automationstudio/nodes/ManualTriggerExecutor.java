package com.automationstudio.nodes;

import com.automationstudio.execution.ExecContext;
import com.automationstudio.execution.NodeExecutor;
import com.automationstudio.execution.NodeResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ManualTriggerExecutor implements NodeExecutor {
  @Override
  public String type() {
    return "manual_trigger";
  }

  @Override
  public NodeResult execute(ExecContext ctx) {
    long start = System.currentTimeMillis();
    Object payload = ctx.config().get("payload");
    Map<String, Object> output = payload instanceof Map
        ? NodeHelpers.toMap(payload)
        : Map.of("value", payload == null ? Map.of() : payload);
    Map<String, Object> out = new LinkedHashMap<>(output);
    return NodeResult.success(out, System.currentTimeMillis() - start);
  }
}
