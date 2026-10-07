package com.automationstudio.api;

import com.automationstudio.execution.ExecContext;
import com.automationstudio.execution.ExecutionNodeRepository;
import com.automationstudio.execution.NodeExecutorRegistry;
import com.automationstudio.execution.NodeResult;
import com.automationstudio.expression.RefResolver;
import com.automationstudio.expression.TemplateRenderer;
import com.automationstudio.security.CredentialService;
import com.automationstudio.workflow.WorkflowDefinition;
import com.automationstudio.workflow.WorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Test Node: manual input &gt; pinData &gt; last successful execution outputs.
 * MISSING_UPSTREAM_DATA lists the nodes to pin or run when nothing resolves.
 */
@RestController
@RequestMapping("/api/nodes/test")
public class NodeController {

  private final NodeExecutorRegistry executors;
  private final WorkflowService workflows;
  private final ExecutionNodeRepository nodeRepo;
  private final CredentialService credentials;
  private final ObjectMapper json;

  public NodeController(
      NodeExecutorRegistry executors,
      WorkflowService workflows,
      ExecutionNodeRepository nodeRepo,
      CredentialService credentials,
      ObjectMapper json) {
    this.executors = executors;
    this.workflows = workflows;
    this.nodeRepo = nodeRepo;
    this.credentials = credentials;
    this.json = json;
  }

  @PostMapping
  public Map<String, Object> test(@RequestBody Map<String, Object> body) {
    Map<String, Object> nodeMap = cast(body.get("node"));
    if (nodeMap == null || nodeMap.get("id") == null || nodeMap.get("type") == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "node with id+type required");
    }
    String nodeId = nodeMap.get("id").toString();
    String nodeType = nodeMap.get("type").toString();
    Map<String, Object> config = cast(nodeMap.getOrDefault("config", Map.of()));

    Map<String, Map<String, Object>> outputs = new LinkedHashMap<>();
    List<String> upstreamIds = new ArrayList<>();
    if (body.get("workflowId") != null) {
      UUID workflowId = UUID.fromString(body.get("workflowId").toString());
      WorkflowDefinition def = workflows.read(workflows.get(workflowId));
      // upstream = all ancestors of the tested node
      List<String> stack = new ArrayList<>();
      for (var e : def.edges()) {
        if (e.target().equals(nodeId)) stack.add(e.source());
      }
      java.util.Set<String> seen = new java.util.HashSet<>();
      while (!stack.isEmpty()) {
        String cur = stack.remove(stack.size() - 1);
        if (!seen.add(cur)) continue;
        upstreamIds.add(cur);
        for (var e : def.edges()) {
          if (e.target().equals(cur)) stack.add(e.source());
        }
      }
      // last successful execution outputs (lowest precedence)
      for (String up : upstreamIds) {
        var latest = nodeRepo.findLatestSuccessful(workflowId, up);
        if (!latest.isEmpty() && latest.get(0).outputJson != null) {
          try {
            outputs.put(up, asMap(json.readValue(latest.get(0).outputJson, Object.class)));
          } catch (Exception ignored) {
          }
        }
      }
      // pinData overlays
      if (def.pinData() != null) {
        for (String up : upstreamIds) {
          if (def.pinData().containsKey(up)) outputs.put(up, asMap(def.pinData().get(up)));
        }
      }
    }

    Map<String, Object> input = cast(body.get("input"));
    if (input == null) input = Map.of();

    // check referenced upstreams resolve
    Set<String> refs = collectRefs(nodeType, config);
    List<String> missing = new ArrayList<>();
    for (String ref : refs) {
      String root = ref.split("[.\\[]", 2)[0];
      if (root.equals("input") || root.equals("execution") || root.equals("credentials")) continue;
      if (!outputs.containsKey(root) && !upstreamIds.isEmpty()
          && upstreamIds.contains(root)) {
        missing.add(root);
      }
    }
    if (!missing.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "MISSING_UPSTREAM_DATA: pin or run nodes " + missing);
    }

    ExecContext ctx = new ExecContext(UUID.randomUUID(), config, input, outputs,
        credentials::resolve, new AtomicBoolean(false)::get, 60000);
    NodeResult result;
    try {
      result = executors.get(nodeType).execute(ctx);
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Test failed: " + e.getMessage());
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("status", result.status());
    out.put("output", result.output());
    out.put("error", result.error() == null ? "" : result.error());
    return out;
  }

  private Set<String> collectRefs(String nodeType, Map<String, Object> config) {
    Set<String> refs = new java.util.LinkedHashSet<>();
    if ("condition".equals(nodeType) && config.get("expression") != null) {
      try {
        refs.addAll(RefResolver.collectRefs(
            new com.automationstudio.expression.ExpressionParser()
                .parse(config.get("expression").toString())));
      } catch (Exception ignored) {
      }
    }
    flatten(config).forEach(v -> refs.addAll(TemplateRenderer.templateRefs(v)));
    return refs;
  }

  private List<String> flatten(Object v) {
    List<String> out = new ArrayList<>();
    if (v instanceof String s) out.add(s);
    else if (v instanceof Map<?, ?> m) m.values().forEach(x -> out.addAll(flatten(x)));
    else if (v instanceof List<?> l) l.forEach(x -> out.addAll(flatten(x)));
    return out;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> cast(Object o) {
    return o instanceof Map ? (Map<String, Object>) o : null;
  }

  private Map<String, Object> asMap(Object o) {
    if (o instanceof Map<?, ?> m) {
      Map<String, Object> out = new LinkedHashMap<>();
      m.forEach((k, v) -> out.put(k.toString(), v));
      return out;
    }
    return Map.of("value", o);
  }
}
