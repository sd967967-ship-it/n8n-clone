package com.automationstudio.workflow;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PieceValidatorTest {

  private final WorkflowValidator validator = new WorkflowValidator();

  private WorkflowDefinition.Node node(String id, String type, Map<String, Object> config) {
    return new WorkflowDefinition.Node(
        id, type, id, new WorkflowDefinition.Position(0, 0), config, Map.of("onError", "stop"));
  }

  private WorkflowDefinition wf(WorkflowDefinition.Node... nodes) {
    var list = List.of(nodes);
    var edges = new java.util.ArrayList<WorkflowDefinition.Edge>();
    for (int i = 0; i + 1 < list.size(); i++) {
      edges.add(new WorkflowDefinition.Edge("e" + i, list.get(i).id(), list.get(i + 1).id(), null));
    }
    return new WorkflowDefinition(1, "x", "", list, edges, Map.of());
  }

  @Test
  void appActionValid() {
    var res = validator.validate(wf(
        node("trigger_1", "manual_trigger", Map.of()),
        node("gh_1", "app_action",
            Map.of("app", "github", "operation", "list_issues", "connection", "my_gh",
                "owner", "acme", "repo", "demo"))));
    assertEquals("VALID", res.status(), res.errors().toString());
  }

  @Test
  void unknownAppRejected() {
    var res = validator.validate(wf(
        node("trigger_1", "manual_trigger", Map.of()),
        node("x_1", "app_action", Map.of("app", "nope", "operation", "get"))));
    assertTrue(res.errors().stream().anyMatch(i -> i.code().equals("UNKNOWN_APP")));
  }

  @Test
  void unknownOperationRejected() {
    var res = validator.validate(wf(
        node("trigger_1", "manual_trigger", Map.of()),
        node("gh_1", "app_action",
            Map.of("app", "github", "operation", "nope", "connection", "my_gh"))));
    assertTrue(res.errors().stream().anyMatch(i -> i.code().equals("UNKNOWN_OPERATION")));
  }

  @Test
  void missingConnectionRejected() {
    var res = validator.validate(wf(
        node("trigger_1", "manual_trigger", Map.of()),
        node("gh_1", "app_action", Map.of("app", "github", "operation", "list_issues"))));
    assertTrue(res.errors().stream().anyMatch(i -> i.code().equals("MISSING_CONNECTION")));
  }

  @Test
  void oauthAppDisabledInV1() {
    var res = validator.validate(wf(
        node("trigger_1", "manual_trigger", Map.of()),
        node("gm_1", "app_action",
            Map.of("app", "gmail", "operation", "list_messages", "connection", "my_gmail"))));
    assertTrue(res.errors().stream().anyMatch(i -> i.code().equals("UNKNOWN_APP")));
  }

  @Test
  void appTriggerAsOnlyTrigger() {
    var res = validator.validate(new WorkflowDefinition(1, "x", "", List.of(
        node("tg_1", "app_trigger", Map.of("app", "telegram", "event", "updates",
            "connection", "my_bot")),
        node("out_1", "output", Map.of("value", "got {{tg_1.output.ok}}"))),
        List.of(new WorkflowDefinition.Edge("e1", "tg_1", "out_1", null)), Map.of()));
    assertEquals("VALID", res.status(), res.errors().toString());
  }
}
