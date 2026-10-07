package com.automationstudio.nodes;

import com.automationstudio.execution.ExecContext;
import com.automationstudio.execution.NodeExecutor;
import com.automationstudio.execution.NodeResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class OutputExecutor implements NodeExecutor {
  @Override
  public String type() {
    return "output";
  }

  @Override
  public NodeResult execute(ExecContext ctx) {
    long start = System.currentTimeMillis();
    Object value = ctx.config().get("value");
    Object rendered;
    List<String> usedSecrets = new ArrayList<>();
    if (value instanceof String s) {
      rendered = NodeHelpers.render(s, ctx.refContext(), null, ctx.secret(), usedSecrets);
    } else if (value == null) {
      rendered = ctx.input();
    } else {
      rendered = NodeHelpers.deepRender(value, ctx.refContext(), null, ctx.secret(), usedSecrets);
    }
    return NodeResult.success(Map.of("value", rendered == null ? "" : rendered),
        System.currentTimeMillis() - start);
  }
}
