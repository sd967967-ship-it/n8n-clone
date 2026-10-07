package com.automationstudio.workflow;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkflowValidatorTest {

  private final WorkflowValidator validator = new WorkflowValidator();

  private WorkflowDefinition.Node node(String id, String type, Map<String, Object> config) {
    return new WorkflowDefinition.Node(
        id, type, id, new WorkflowDefinition.Position(0, 0), config, Map.of("onError", "stop"));
  }

  private WorkflowDefinition validWorkflow() {
    return new WorkflowDefinition(1, "demo", "", List.of(
        node("trigger_1", "manual_trigger", Map.of("payload", Map.of("score", 72))),
        node("cond_1", "condition", Map.of("expression", "trigger_1.output.score >= 50")),
        node("out_yes", "output", Map.of("value", "Passed")),
        node("out_no", "output", Map.of("value", "Failed"))),
        List.of(
            new WorkflowDefinition.Edge("e1", "trigger_1", "cond_1", null),
            new WorkflowDefinition.Edge("e2", "cond_1", "out_yes", "true"),
            new WorkflowDefinition.Edge("e3", "cond_1", "out_no", "false")),
        Map.of());
  }

  @Test
  void validWorkflowPasses() {
    var res = validator.validate(validWorkflow());
    assertEquals("VALID", res.status(), res.errors().toString());
  }

  @Test
  void triggerCountEnforced() {
    var wf = new WorkflowDefinition(1, "x", "", List.of(
        node("a", "output", Map.of())), List.of(), Map.of());
    var res = validator.validate(wf);
    assertEquals("DRAFT", res.status());
    assertTrue(res.errors().stream().anyMatch(i -> i.code().equals("TRIGGER_COUNT")));
  }

  @Test
  void badExpressionAndUnknownFunction() {
    // minimal edges to avoid extra noise
    var wf2 = new WorkflowDefinition(1, "x", "", List.of(
        node("trigger_1", "manual_trigger", Map.of()),
        node("cond_1", "condition", Map.of("expression", "trigger_1.output.x =="))),
        List.of(new WorkflowDefinition.Edge("e1", "trigger_1", "cond_1", null)), Map.of());
    assertTrue(validator.validate(wf2).errors().stream().anyMatch(i -> i.code().equals("BAD_EXPRESSION")));
    var wf3 = new WorkflowDefinition(1, "x", "", List.of(
        node("trigger_1", "manual_trigger", Map.of()),
        node("cond_1", "condition", Map.of("expression", "frobnicate(trigger_1.output.x) == 1"))),
        List.of(new WorkflowDefinition.Edge("e1", "trigger_1", "cond_1", null)), Map.of());
    assertTrue(validator.validate(wf3).errors().stream().anyMatch(i -> i.code().equals("BAD_EXPRESSION")));
  }

  @Test
  void refNotUpstreamRejected() {
    var wf = new WorkflowDefinition(1, "x", "", List.of(
        node("trigger_1", "manual_trigger", Map.of()),
        node("http_1", "http_request",
            Map.of("method", "GET", "url", "https://api.open-meteo.com/v1/forecast")),
        node("cond_1", "condition", Map.of("expression", "later.output.x == true")),
        node("later", "output", Map.of("value", "x"))),
        List.of(
            new WorkflowDefinition.Edge("e1", "trigger_1", "http_1", null),
            new WorkflowDefinition.Edge("e2", "http_1", "cond_1", null),
            new WorkflowDefinition.Edge("e3", "cond_1", "later", null)),
        Map.of());
    var res = validator.validate(wf);
    assertTrue(res.errors().stream().anyMatch(i -> i.code().equals("REF_NOT_UPSTREAM")),
        res.errors().toString());
  }

  @Test
  void ssrfBlocksLoopback() {
    var wf = new WorkflowDefinition(1, "x", "", List.of(
        node("trigger_1", "manual_trigger", Map.of()),
        node("http_1", "http_request",
            Map.of("method", "GET", "url", "http://127.0.0.1:8080/admin"))),
        List.of(new WorkflowDefinition.Edge("e1", "trigger_1", "http_1", null)), Map.of());
    assertTrue(validator.validate(wf).errors().stream().anyMatch(i -> i.code().equals("URL_BLOCKED")));
  }

  @Test
  void credentialsRejectedInLlmPrompt() {
    var wf = new WorkflowDefinition(1, "x", "", List.of(
        node("trigger_1", "manual_trigger", Map.of()),
        node("llm_1", "llm", Map.of("prompt", "hi {{credentials.openai}}"))),
        List.of(new WorkflowDefinition.Edge("e1", "trigger_1", "llm_1", null)), Map.of());
    var res = validator.validate(wf, Set.of("openai"));
    assertTrue(res.errors().stream().anyMatch(i -> i.code().equals("CREDENTIAL_NOT_ALLOWED")),
        res.errors().toString());
  }

  @Test
  void unreachableIsWarningOnly() {
    var wf = new WorkflowDefinition(1, "x", "", List.of(
        node("trigger_1", "manual_trigger", Map.of()),
        node("out_1", "output", Map.of("value", "ok")),
        node("orphan", "output", Map.of("value", "lost"))),
        List.of(new WorkflowDefinition.Edge("e1", "trigger_1", "out_1", null)), Map.of());
    var res = validator.validate(wf);
    assertEquals("VALID", res.status());
    assertTrue(res.warnings().stream().anyMatch(i -> i.code().equals("UNREACHABLE_NODE")));
  }

  @Test
  void duplicateIdsAndBadHandle() {
    var wf = new WorkflowDefinition(1, "x", "", List.of(
        node("trigger_1", "manual_trigger", Map.of()),
        node("trigger_1", "output", Map.of()),
        node("cond_1", "condition", Map.of("expression", "true"))),
        List.of(
            new WorkflowDefinition.Edge("e1", "trigger_1", "cond_1", "maybe")),
        Map.of());
    var res = validator.validate(wf);
    assertTrue(res.errors().stream().anyMatch(i -> i.code().equals("DUPLICATE_NODE_ID")));
    assertTrue(res.errors().stream().anyMatch(i -> i.code().equals("EDGE_BAD_HANDLE")));
  }
}
