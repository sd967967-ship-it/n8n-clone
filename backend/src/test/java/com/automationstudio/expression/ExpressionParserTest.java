package com.automationstudio.expression;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExpressionParserTest {

  private final ExpressionParser parser = new ExpressionParser();

  @Test
  void parsesSimpleComparison() {
    Expr e = parser.parse("trigger_1.output.score >= 50");
    assertInstanceOf(Expr.Comparison.class, e);
  }

  @Test
  void precedenceAndOrNot() {
    Expr e = parser.parse("!a.output.x == true || b.output.y != false && c.output.z in d.output.list");
    assertInstanceOf(Expr.Or.class, e);
  }

  @Test
  void functionsAndNesting() {
    Expr e = parser.parse("contains(lower(trigger_1.output.name), \"test\") && exists(http_1.output.body)");
    assertInstanceOf(Expr.And.class, e);
  }

  @Test
  void refsWithIndexes() {
    Expr e = parser.parse("http_1.output.body.items[0].name == 'x'");
    assertInstanceOf(Expr.Comparison.class, e);
  }

  @Test
  void rejectsBadSyntax() {
    assertThrows(ParseException.class, () -> parser.parse(""));
    assertThrows(ParseException.class, () -> parser.parse("a.output.x =="));
    assertThrows(ParseException.class, () -> parser.parse("contains("));
    assertThrows(ParseException.class, () -> parser.parse("a.output.x == 'unterminated"));
  }

  @Test
  void evaluatorTypeRules() {
    ExpressionEvaluator eval = new ExpressionEvaluator();
    Map<String, Object> score = Map.of("score", 72, "name", "Hello");
    Map<String, Object> ctx = Map.of("trigger_1", Map.of("output", score));
    assertTrue(eval.evaluateBoolean(parser.parse("trigger_1.output.score >= 50"), ctx));
    assertFalse(eval.evaluateBoolean(parser.parse("trigger_1.output.score < 50"), ctx));
    // mismatched types -> false, not error
    assertFalse(eval.evaluateBoolean(parser.parse("trigger_1.output.name > 50"), ctx));
    assertTrue(eval.evaluateBoolean(
        parser.parse("contains(lower(trigger_1.output.name), \"hell\")"), ctx));
    assertTrue(eval.evaluateBoolean(parser.parse("exists(trigger_1.output.score)"), ctx));
    assertFalse(eval.evaluateBoolean(parser.parse("exists(trigger_1.output.nope)"), ctx));
    // missing path renders null -> comparison false
    assertFalse(eval.evaluateBoolean(parser.parse("trigger_1.output.nope == true"), ctx));
  }

  @Test
  void templateRenderer() {
    TemplateRenderer r = new TemplateRenderer();
    Map<String, Object> item = Map.of("name", "a");
    Map<String, Object> body = Map.of("items", List.of(item));
    Map<String, Object> ctx = Map.of("http_1", Map.of("output", Map.of("body", body)));
    var res = r.render("Temp is {{http_1.output.body.items[0].name}}!", ctx);
    assertEquals("Temp is a!", res.text());
    assertTrue(res.missingRefs().isEmpty());
    var missing = r.render("Hi {{http_1.output.nope}}", ctx);
    assertEquals("Hi ", missing.text());
    assertEquals(List.of("http_1.output.nope"), missing.missingRefs());
  }
}
