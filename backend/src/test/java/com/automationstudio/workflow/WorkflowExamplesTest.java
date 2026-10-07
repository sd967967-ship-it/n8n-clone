package com.automationstudio.workflow;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The bundled demo workflows must stay VALID against the real validator. */
class WorkflowExamplesTest {

  private final ObjectMapper json = new ObjectMapper();
  private final WorkflowValidator validator = new WorkflowValidator();

  @ParameterizedTest
  @ValueSource(strings = {"weather-ai.json", "api-summary.json", "condition-demo.json", "classifier.json"})
  void bundledWorkflowIsValid(String file) throws Exception {
    WorkflowDefinition wf;
    try (InputStream in = getClass().getResourceAsStream("/workflows/" + file)) {
      assertNotNull(in, "missing test resource: " + file);
      wf = json.readValue(in, WorkflowDefinition.class);
    }
    var res = validator.validate(wf);
    assertEquals("VALID", res.status(), file + " errors: " + res.errors());
    assertTrue(res.warnings().stream().noneMatch(w -> w.code().equals("UNREACHABLE_NODE")),
        file + " warnings: " + res.warnings());
  }

  @Test
  void allFourPresent() {
    assertEquals(4, List.of("weather-ai.json", "api-summary.json", "condition-demo.json", "classifier.json")
        .stream().filter(f -> getClass().getResourceAsStream("/workflows/" + f) != null).count());
  }
}
