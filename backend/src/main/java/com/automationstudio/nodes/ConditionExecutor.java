package com.automationstudio.nodes;

import com.automationstudio.execution.ExecContext;
import com.automationstudio.execution.NodeExecutor;
import com.automationstudio.execution.NodeResult;
import com.automationstudio.expression.Expr;
import com.automationstudio.expression.ExpressionEvaluator;
import com.automationstudio.expression.ExpressionParser;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ConditionExecutor implements NodeExecutor {
  private final ExpressionParser parser = new ExpressionParser();
  private final ExpressionEvaluator evaluator = new ExpressionEvaluator();

  @Override
  public String type() {
    return "condition";
  }

  @Override
  public NodeResult execute(ExecContext ctx) {
    long start = System.currentTimeMillis();
    String expression = String.valueOf(ctx.config().get("expression"));
    Expr ast = parser.parse(expression);
    boolean branch = evaluator.evaluateBoolean(ast, ctx.refContext());
    // pass-through of input; engine routes via the _branch flag
    Map<String, Object> out = new LinkedHashMap<>(ctx.input());
    out.put("_branch", branch);
    return NodeResult.success(out, System.currentTimeMillis() - start);
  }
}
