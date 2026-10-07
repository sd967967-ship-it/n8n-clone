package com.automationstudio.nodes;

import com.automationstudio.execution.ExecContext;
import com.automationstudio.execution.NodeExecutor;
import com.automationstudio.execution.NodeResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class TransformExecutor implements NodeExecutor {
  @Override
  public String type() {
    return "transform";
  }

  @Override
  @SuppressWarnings("unchecked")
  public NodeResult execute(ExecContext ctx) {
    long start = System.currentTimeMillis();
    Object mapping = ctx.config().get("mapping");
    if (!(mapping instanceof Map)) {
      return NodeResult.failure("mapping must be an object", System.currentTimeMillis() - start);
    }
    Map<String, Object> out = new LinkedHashMap<>();
    List<String> usedSecrets = new ArrayList<>();
    for (var e : ((Map<?, ?>) mapping).entrySet()) {
      Object v = e.getValue();
      if (v instanceof String s && new com.automationstudio.expression.TemplateRenderer().isBareRef(s)) {
        out.put(e.getKey().toString(), NodeHelpers.resolveBareRef(s, ctx.refContext()));
      } else if (v instanceof String s) {
        out.put(e.getKey().toString(),
            NodeHelpers.render(s, ctx.refContext(), null, ctx.secret(), usedSecrets));
      } else {
        out.put(e.getKey().toString(), v);
      }
    }
    return NodeResult.success(out, System.currentTimeMillis() - start);
  }
}
