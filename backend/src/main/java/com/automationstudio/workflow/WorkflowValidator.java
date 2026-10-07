package com.automationstudio.workflow;

import com.automationstudio.expression.Expr;
import com.automationstudio.expression.ExpressionEvaluator;
import com.automationstudio.expression.ExpressionParser;
import com.automationstudio.expression.ParseException;
import com.automationstudio.expression.RefResolver;
import com.automationstudio.expression.TemplateRenderer;
import com.automationstudio.pieces.PieceManifest;
import com.automationstudio.pieces.PieceRegistry;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Server-side workflow validator. Browser is never trusted.
 * Returns errors (block run, status DRAFT) and warnings (never block).
 */
@Component
public class WorkflowValidator {

  private static final Set<String> MVP_TYPES =
      Set.of("manual_trigger", "http_request", "llm", "condition", "transform", "output",
          "app_action", "app_trigger");

  private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_]+");
  private static final Set<String> ALLOWED_ON_ERROR = Set.of("stop", "continue", "route");

  // fields where {{credentials.NAME}} is allowed
  private static final Set<String> CREDENTIAL_ALLOWED_PATHS =
      Set.of("http_request.headers", "http_request.auth", "http_request.query", "postgres.credential");

  private final ExpressionParser parser = new ExpressionParser();

  public record ValidationResult(String status, List<ValidationIssue> errors, List<ValidationIssue> warnings) {
    public List<ValidationIssue> issues() {
      List<ValidationIssue> all = new ArrayList<>(errors);
      all.addAll(warnings);
      return all;
    }
  }

  /** Validate with no credential inventory (skips CREDENTIAL_NOT_FOUND). */
  public ValidationResult validate(WorkflowDefinition wf) {
    return validate(wf, null);
  }

  public ValidationResult validate(WorkflowDefinition wf, Set<String> knownCredentials) {
    List<ValidationIssue> errors = new ArrayList<>();
    List<ValidationIssue> warnings = new ArrayList<>();
    if (wf == null) {
      errors.add(ValidationIssue.error("MISSING_CONFIG", null, "", "Workflow is null"));
      return new ValidationResult("DRAFT", errors, warnings);
    }
    List<WorkflowDefinition.Node> nodes = wf.nodes() == null ? List.of() : wf.nodes();
    List<WorkflowDefinition.Edge> edges = wf.edges() == null ? List.of() : wf.edges();

    // --- node ids ---
    Map<String, WorkflowDefinition.Node> byId = new LinkedHashMap<>();
    Set<String> seen = new HashSet<>();
    for (WorkflowDefinition.Node n : nodes) {
      if (n.id() == null || n.id().isBlank() || !ID_PATTERN.matcher(n.id()).matches()) {
        errors.add(ValidationIssue.error("MISSING_NODE_ID", n.id(), "id",
            "Node id must match [a-z0-9_]+"));
        continue;
      }
      if (!seen.add(n.id())) {
        errors.add(ValidationIssue.error("DUPLICATE_NODE_ID", n.id(), "id", "Duplicate node id"));
      } else {
        byId.put(n.id(), n);
      }
      if (n.type() == null || !MVP_TYPES.contains(n.type())) {
        errors.add(ValidationIssue.error("UNKNOWN_NODE_TYPE", n.id(), "type",
            "Unknown node type: " + n.type() + " (MVP: " + MVP_TYPES + ")"));
      }
      if (n.position() == null) {
        errors.add(ValidationIssue.error("MISSING_CONFIG", n.id(), "position", "Canvas position is required"));
      }
    }

    // --- exactly one trigger ---
    List<WorkflowDefinition.Node> triggers =
        nodes.stream().filter(n -> n.type() != null && n.type().endsWith("_trigger")).toList();
    if (triggers.size() != 1) {
      errors.add(ValidationIssue.error("TRIGGER_COUNT", null, "nodes",
          "Workflow must have exactly one trigger, found " + triggers.size()));
    }

    // --- edges ---
    Map<String, List<WorkflowDefinition.Edge>> outgoing = new HashMap<>();
    Map<String, List<WorkflowDefinition.Edge>> incoming = new HashMap<>();
    for (WorkflowDefinition.Node n : nodes) {
      if (n.id() != null) {
        outgoing.putIfAbsent(n.id(), new ArrayList<>());
        incoming.putIfAbsent(n.id(), new ArrayList<>());
      }
    }
    for (WorkflowDefinition.Edge e : edges) {
      boolean badSource = e.source() == null || !byId.containsKey(e.source());
      boolean badTarget = e.target() == null || !byId.containsKey(e.target());
      if (badSource || badTarget) {
        errors.add(ValidationIssue.error("EDGE_UNKNOWN_NODE", null, "edges",
            "Edge references unknown node: " + e.source() + " -> " + e.target()));
        continue;
      }
      outgoing.get(e.source()).add(e);
      incoming.get(e.target()).add(e);
      // handle rules
      WorkflowDefinition.Node src = byId.get(e.source());
      String handle = e.sourceHandle();
      boolean isCondition = "condition".equals(src.type());
      boolean routeOnError = "route".equals(onError(src));
      if (handle == null || handle.isEmpty()) {
        // normal edge, always fine
      } else if (isCondition && (handle.equals("true") || handle.equals("false"))) {
        // ok
      } else if (routeOnError && handle.equals("error")) {
        // ok
      } else {
        errors.add(ValidationIssue.error("EDGE_BAD_HANDLE", e.source(), "edges",
            "Handle '" + handle + "' not valid for source type " + src.type()));
      }
    }

    // --- cycle detection (Kahn) ---
    if (hasCycle(byId.keySet(), outgoing)) {
      errors.add(ValidationIssue.error("CYCLE_DETECTED", null, "edges", "Graph must be a DAG"));
    }

    // --- reachability from trigger (warning) ---
    if (triggers.size() == 1) {
      Set<String> reachable = reachableFrom(triggers.get(0).id(), outgoing);
      for (String id : byId.keySet()) {
        if (!reachable.contains(id)) {
          warnings.add(ValidationIssue.warning("UNREACHABLE_NODE", id, "",
              "Node cannot be reached from the trigger"));
        }
      }
    }

    // --- upstream map (ancestors) for ref checks ---
    Map<String, Set<String>> upstream = computeUpstream(byId.keySet(), incoming);

    // --- per-node config ---
    for (WorkflowDefinition.Node n : nodes) {
      if (n.id() == null || !byId.containsKey(n.id())) continue;
      validateSettings(n, errors);
      validateNodeConfig(n, errors, knownCredentials, byId.keySet(), upstream.getOrDefault(n.id(), Set.of()));
    }

    String status = errors.isEmpty() ? "VALID" : "DRAFT";
    return new ValidationResult(status, List.copyOf(errors), List.copyOf(warnings));
  }

  private String onError(WorkflowDefinition.Node n) {
    if (n.settings() == null) return "stop";
    Object v = n.settings().get("onError");
    return v == null ? "stop" : v.toString();
  }

  private void validateSettings(WorkflowDefinition.Node n, List<ValidationIssue> errors) {
    if (n.settings() == null) return;
    Object onError = n.settings().get("onError");
    if (onError != null && !ALLOWED_ON_ERROR.contains(onError.toString())) {
      errors.add(ValidationIssue.error("MISSING_CONFIG", n.id(), "settings.onError",
          "onError must be stop|continue|route"));
    }
    Object retries = n.settings().get("retries");
    if (retries != null) {
      try {
        int r = Integer.parseInt(retries.toString());
        if (r < 0 || r > 5) {
          errors.add(ValidationIssue.error("MISSING_CONFIG", n.id(), "settings.retries",
              "retries must be 0-5"));
        }
      } catch (NumberFormatException e) {
        errors.add(ValidationIssue.error("MISSING_CONFIG", n.id(), "settings.retries",
            "retries must be 0-5"));
      }
    }
  }

  @SuppressWarnings("unchecked")
  private void validateNodeConfig(
      WorkflowDefinition.Node n,
      List<ValidationIssue> errors,
      Set<String> knownCredentials,
      Set<String> allIds,
      Set<String> upstreamIds) {
    Map<String, Object> cfg = n.config() == null ? Map.of() : n.config();
    switch (n.type() == null ? "" : n.type()) {
      case "manual_trigger" -> { /* no required config */ }
      case "http_request" -> {
        if (blank(cfg.get("method"))) {
          errors.add(ValidationIssue.error("MISSING_CONFIG", n.id(), "config.method", "HTTP method is required"));
        }
        Object url = cfg.get("url");
        if (blank(url)) {
          errors.add(ValidationIssue.error("MISSING_CONFIG", n.id(), "config.url", "URL is required"));
        } else if (url instanceof String s && !s.contains("{{")) {
          if (urlBlocked(s)) {
            errors.add(ValidationIssue.error("URL_BLOCKED", n.id(), "config.url",
                "URL is blocked by the SSRF guard: " + s));
          }
        }
      }
      case "llm" -> {
        if (blank(cfg.get("prompt"))) {
          errors.add(ValidationIssue.error("MISSING_CONFIG", n.id(), "config.prompt", "Prompt is required"));
        }
      }
      case "condition" -> {
        Object expr = cfg.get("expression");
        if (blank(expr)) {
          errors.add(ValidationIssue.error("MISSING_CONFIG", n.id(), "config.expression",
              "Expression is required"));
        } else {
          try {
            Expr ast = parser.parse(expr.toString());
            // unknown function check
            List<String> badFns = new ArrayList<>();
            collectBadFunctions(ast, badFns);
            if (!badFns.isEmpty()) {
              errors.add(ValidationIssue.error("BAD_EXPRESSION", n.id(), "config.expression",
                  "Unknown function(s): " + badFns));
            }
            checkRefs(n.id(), RefResolver.collectRefs(ast), errors, allIds, upstreamIds);
          } catch (ParseException e) {
            errors.add(ValidationIssue.error("BAD_EXPRESSION", n.id(), "config.expression",
                "Expression parse error: " + e.getMessage()));
          }
        }
      }
      case "transform" -> {
        Object mapping = cfg.get("mapping");
        if (!(mapping instanceof Map) || ((Map<?, ?>) mapping).isEmpty()) {
          errors.add(ValidationIssue.error("MISSING_CONFIG", n.id(), "config.mapping",
              "Mapping object is required"));
        }
      }
      case "output" -> { /* value optional */ }
      case "app_action", "app_trigger" -> validateAppNode(n, cfg, errors, allIds, upstreamIds);
      default -> { /* unknown type already reported */ }
    }

    // --- template refs + credential rules across all string config values ---
    Map<String, String> stringFields = flattenStrings(cfg, "config");
    for (Map.Entry<String, String> field : stringFields.entrySet()) {
      Set<String> refs = TemplateRenderer.templateRefs(field.getValue());
      checkRefs(n.id(), refs, errors, allIds, upstreamIds);
      for (String ref : refs) {
        if (ref.equals("credentials") || ref.startsWith("credentials.")) {
          String credPath = n.type() + "." + field.getKey().substring("config.".length()).split("\\.")[0];
          // normalize nested: headers.x -> http_request.headers
          String[] parts = field.getKey().split("\\.");
          String topField = parts.length > 1 ? parts[1] : "";
          String allowKey = (n.type() == null ? "" : n.type()) + "." + topField;
          if (!CREDENTIAL_ALLOWED_PATHS.contains(allowKey) && !allowKey.equals("postgres.credential")) {
            errors.add(ValidationIssue.error("CREDENTIAL_NOT_ALLOWED", n.id(), field.getKey(),
                "{{credentials.*}} only allowed in http_request headers/auth/query: " + ref));
          } else if (knownCredentials != null) {
            String credName = ref.length() > "credentials.".length()
                ? ref.substring("credentials.".length()).split("[.\\[]")[0] : "";
            if (!credName.isEmpty() && !knownCredentials.contains(credName)) {
              errors.add(ValidationIssue.error("CREDENTIAL_NOT_FOUND", n.id(), field.getKey(),
                  "Unknown credential: " + credName));
            }
          }
          // never in LLM prompts (covered above, but explicit message)
          if ("llm".equals(n.type())) {
            errors.add(ValidationIssue.error("CREDENTIAL_NOT_ALLOWED", n.id(), field.getKey(),
                "Credentials must never be interpolated into LLM prompts"));
          }
        }
      }
    }
    // bare-ref values in transform mapping copy typed values: validate roots too
    if ("transform".equals(n.type()) && cfg.get("mapping") instanceof Map<?, ?> mapping) {
      TemplateRenderer tr = new TemplateRenderer();
      for (Map.Entry<?, ?> e : mapping.entrySet()) {
        Object v = e.getValue();
        if (v instanceof String s && tr.isBareRef(s)) {
          checkRefs(n.id(), Set.of(s), errors, allIds, upstreamIds);
        }
      }
    }
  }

  private void collectBadFunctions(Expr e, List<String> out) {
    if (e instanceof Expr.Call c) {
      if (!ExpressionEvaluator.isAllowedFunction(c.name())) out.add(c.name());
      c.args().forEach(a -> collectBadFunctions(a, out));
    } else if (e instanceof Expr.Or o) {
      o.terms().forEach(t -> collectBadFunctions(t, out));
    } else if (e instanceof Expr.And a) {
      a.terms().forEach(t -> collectBadFunctions(t, out));
    } else if (e instanceof Expr.Not n) {
      collectBadFunctions(n.inner(), out);
    } else if (e instanceof Expr.Comparison c) {
      collectBadFunctions(c.left(), out);
      collectBadFunctions(c.right(), out);
    }
  }

  // --- app pieces (generic app_action / app_trigger) ---

  private static volatile PieceRegistry registry;

  private static PieceRegistry pieces() {
    if (registry == null) {
      synchronized (WorkflowValidator.class) {
        if (registry == null) registry = PieceRegistry.loadAll();
      }
    }
    return registry;
  }

  private void validateAppNode(
      WorkflowDefinition.Node n,
      Map<String, Object> cfg,
      List<ValidationIssue> errors,
      Set<String> allIds,
      Set<String> upstreamIds) {
    Object app = cfg.get("app");
    if (blank(app)) {
      errors.add(ValidationIssue.error("MISSING_CONFIG", n.id(), "config.app", "App is required"));
      return;
    }
    PieceManifest manifest;
    try {
      manifest = pieces().get(app.toString());
    } catch (IllegalStateException e) {
      errors.add(ValidationIssue.error("UNKNOWN_APP", n.id(), "config.app",
          "Piece registry failed to load: " + e.getMessage()));
      return;
    }
    if (manifest == null) {
      errors.add(ValidationIssue.error("UNKNOWN_APP", n.id(), "config.app",
          "Unknown app: " + app));
      return;
    }
    if (!manifest.enabled()) {
      errors.add(ValidationIssue.error("UNKNOWN_APP", n.id(), "config.app",
          "App '" + app + "' needs OAuth2 (deferred to the OAuth slice)"));
      return;
    }
    boolean isTrigger = "app_trigger".equals(n.type());
    if (isTrigger) {
      Object event = cfg.get("event");
      if (blank(event)) {
        errors.add(ValidationIssue.error("MISSING_CONFIG", n.id(), "config.event", "Event is required"));
      } else if (manifest.triggers() == null
          || manifest.triggers().stream().noneMatch(t -> t.id().equals(event.toString()))) {
        errors.add(ValidationIssue.error("UNKNOWN_OPERATION", n.id(), "config.event",
            "Unknown event '" + event + "' for app " + app));
      }
    } else {
      Object op = cfg.get("operation");
      if (blank(op)) {
        errors.add(ValidationIssue.error("MISSING_CONFIG", n.id(), "config.operation",
            "Operation is required"));
      } else if (manifest.operations().stream().noneMatch(o -> o.id().equals(op.toString()))) {
        errors.add(ValidationIssue.error("UNKNOWN_OPERATION", n.id(), "config.operation",
            "Unknown operation '" + op + "' for app " + app));
      }
    }
    // connection required unless auth.type == none
    String authType = manifest.auth() == null ? "none" : manifest.auth().type();
    if (!"none".equals(authType) && blank(cfg.get("connection"))) {
      errors.add(ValidationIssue.error("MISSING_CONNECTION", n.id(), "config.connection",
          "Connection is required for app " + app));
    }
  }

  private void checkRefs(
      String nodeId, Set<String> refs, List<ValidationIssue> errors,
      Set<String> allIds, Set<String> upstreamIds) {
    for (String ref : refs) {
      String root = ref.split("[.\\[]", 2)[0];
      if (root.equals("input") || root.equals("execution") || root.equals("credentials")) continue;
      if (!allIds.contains(root)) {
        errors.add(ValidationIssue.error("REF_UNKNOWN_NODE", nodeId, "config",
            "Reference to unknown node: " + ref));
      } else if (!upstreamIds.contains(root)) {
        errors.add(ValidationIssue.error("REF_NOT_UPSTREAM", nodeId, "config",
            "Reference to node that is not upstream: " + ref));
      }
    }
  }

  private boolean blank(Object o) {
    return o == null || o.toString().isBlank();
  }

  private Map<String, String> flattenStrings(Map<String, Object> cfg, String prefix) {
    Map<String, String> out = new LinkedHashMap<>();
    flattenInto(cfg, prefix, out);
    return out;
  }

  @SuppressWarnings("unchecked")
  private void flattenInto(Object v, String path, Map<String, String> out) {
    if (v instanceof String s) {
      out.put(path, s);
    } else if (v instanceof Map<?, ?> m) {
      for (Map.Entry<?, ?> e : m.entrySet()) {
        flattenInto(e.getValue(), path + "." + e.getKey(), out);
      }
    } else if (v instanceof List<?> l) {
      for (int i = 0; i < l.size(); i++) flattenInto(l.get(i), path + "[" + i + "]", out);
    }
  }

  // --- graph helpers ---

  private boolean hasCycle(Set<String> ids, Map<String, List<WorkflowDefinition.Edge>> outgoing) {
    Map<String, Integer> indeg = new HashMap<>();
    ids.forEach(id -> indeg.put(id, 0));
    for (String id : ids) {
      for (WorkflowDefinition.Edge e : outgoing.getOrDefault(id, List.of())) {
        indeg.merge(e.target(), 1, Integer::sum);
      }
    }
    ArrayDeque<String> q = new ArrayDeque<>();
    indeg.forEach((id, d) -> { if (d == 0) q.add(id); });
    int visited = 0;
    while (!q.isEmpty()) {
      String cur = q.poll();
      visited++;
      for (WorkflowDefinition.Edge e : outgoing.getOrDefault(cur, List.of())) {
        int d = indeg.merge(e.target(), -1, Integer::sum);
        if (d == 0) q.add(e.target());
      }
    }
    return visited != ids.size();
  }

  private Set<String> reachableFrom(String start, Map<String, List<WorkflowDefinition.Edge>> outgoing) {
    Set<String> seen = new HashSet<>();
    ArrayDeque<String> q = new ArrayDeque<>(List.of(start));
    seen.add(start);
    while (!q.isEmpty()) {
      String cur = q.poll();
      for (WorkflowDefinition.Edge e : outgoing.getOrDefault(cur, List.of())) {
        if (seen.add(e.target())) q.add(e.target());
      }
    }
    return seen;
  }

  private Map<String, Set<String>> computeUpstream(
      Set<String> ids, Map<String, List<WorkflowDefinition.Edge>> incoming) {
    Map<String, Set<String>> up = new HashMap<>();
    for (String id : ids) {
      Set<String> anc = new HashSet<>();
      ArrayDeque<String> q = new ArrayDeque<>();
      for (WorkflowDefinition.Edge e : incoming.getOrDefault(id, List.of())) q.add(e.source());
      while (!q.isEmpty()) {
        String cur = q.poll();
        if (!anc.add(cur)) continue;
        for (WorkflowDefinition.Edge e : incoming.getOrDefault(cur, List.of())) q.add(e.source());
      }
      up.put(id, anc);
    }
    return up;
  }

  // --- static SSRF check (runtime SsrfGuard re-checks DNS + redirects) ---

  static boolean urlBlocked(String url) {
    URI uri;
    try {
      uri = new URI(url);
    } catch (Exception e) {
      return true;
    }
    String scheme = uri.getScheme();
    if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
      return true;
    }
    String host = uri.getHost();
    if (host == null || host.isBlank()) return true;
    String h = host.toLowerCase();
    if (h.equals("localhost") || h.endsWith(".local") || h.endsWith(".internal")
        || h.equals("metadata.google.internal")) {
      return true;
    }
    // literal IP check
    try {
      // strip brackets for IPv6 literals
      String ip = h.startsWith("[") && h.endsWith("]") ? h.substring(1, h.length() - 1) : h;
      InetAddress addr = InetAddress.getByName(ip);
      if (!ip.equals(addr.getHostAddress()) && !isIpLiteral(ip)) {
        return false; // hostname, runtime guard resolves DNS
      }
      return addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isMulticastAddress()
          || addr.isSiteLocalAddress() || isCgnat(addr) || isDocumentation(addr);
    } catch (Exception e) {
      return false;
    }
  }

  private static boolean isIpLiteral(String h) {
    return h.matches("[0-9.]+") || h.contains(":");
  }

  private static boolean isCgnat(InetAddress addr) {
    byte[] b = addr.getAddress();
    return b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xFF) >= 64 && (b[1] & 0xFF) <= 127;
  }

  private static boolean isDocumentation(InetAddress addr) {
    byte[] b = addr.getAddress();
    if (b.length == 4) {
      int a = b[0] & 0xFF, c = b[2] & 0xFF;
      return (a == 192 && (b[1] & 0xFF) == 0 && c == 2) || (a == 198 && (b[1] & 0xFF) == 51 && c == 100)
          || (a == 203 && (b[1] & 0xFF) == 0 && c == 113);
    }
    return false;
  }
}
