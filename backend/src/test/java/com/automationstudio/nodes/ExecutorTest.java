package com.automationstudio.nodes;

import static org.junit.jupiter.api.Assertions.*;

import com.automationstudio.execution.ExecContext;
import com.automationstudio.execution.NodeResult;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ExecutorTest {

  private static final Map<String, Map<String, Object>> NO_OUTPUTS = Map.of();

  private ExecContext ctx(Map<String, Object> config, Map<String, Object> input,
      Map<String, Map<String, Object>> outputs) {
    return new ExecContext(UUID.randomUUID(), config, input, outputs,
        name -> { throw new IllegalArgumentException("no secret " + name); },
        new AtomicBoolean(false)::get, 60000);
  }

  private Map<String, Map<String, Object>> oneOutput(String nodeId, Map<String, Object> output) {
    Map<String, Map<String, Object>> m = new HashMap<>();
    m.put(nodeId, output);
    return m;
  }

  private Map<String, Object> strMap(Object... kv) {
    Map<String, Object> m = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
    return m;
  }

  @Test
  void manualTriggerReturnsPayload() {
    var e = new ManualTriggerExecutor();
    NodeResult r = e.execute(ctx(strMap("payload", strMap("score", 72)), Map.of(), NO_OUTPUTS));
    assertEquals("SUCCESS", r.status());
    assertEquals(72, r.output().get("score"));
  }

  @Test
  void conditionBranches() {
    var e = new ConditionExecutor();
    var outputs = oneOutput("trigger_1", strMap("score", 72));
    NodeResult t = e.execute(
        ctx(strMap("expression", "trigger_1.output.score >= 50"), Map.of(), outputs));
    assertEquals("SUCCESS", t.status());
    assertEquals(true, t.output().get("_branch"));
    NodeResult f = e.execute(
        ctx(strMap("expression", "trigger_1.output.score < 50"), Map.of(), outputs));
    assertEquals(false, f.output().get("_branch"));
  }

  @Test
  void transformCopiesAndTemplates() {
    var e = new TransformExecutor();
    var outputs = oneOutput("http_1", strMap("temp", 21));
    NodeResult r = e.execute(ctx(
        strMap("mapping", strMap("t", "http_1.output.temp", "label", "Temp {{http_1.output.temp}}C")),
        Map.of(), outputs));
    assertEquals("SUCCESS", r.status());
    assertEquals(21, r.output().get("t"));
    assertEquals("Temp 21C", r.output().get("label"));
  }

  @Test
  void outputRendersValue() {
    var e = new OutputExecutor();
    var outputs = oneOutput("llm_1", strMap("reason", "rain"));
    NodeResult r = e.execute(
        ctx(strMap("value", "Take umbrella: {{llm_1.output.reason}}"), Map.of(), outputs));
    assertEquals("SUCCESS", r.status());
    assertEquals("Take umbrella: rain", r.output().get("value"));
  }

  @Test
  void outputDefaultsToInput() {
    var e = new OutputExecutor();
    NodeResult r = e.execute(ctx(Map.of(), strMap("a", 1), NO_OUTPUTS));
    assertEquals(strMap("a", 1), r.output().get("value"));
  }
}
