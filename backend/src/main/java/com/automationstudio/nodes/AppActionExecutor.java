package com.automationstudio.nodes;

import com.automationstudio.execution.ExecContext;
import com.automationstudio.execution.NodeExecutor;
import com.automationstudio.execution.NodeResult;
import com.automationstudio.pieces.PieceManifest;
import com.automationstudio.pieces.PieceRegistry;
import com.automationstudio.security.SsrfGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Generic piece executor: manifest operation + typed connection, rendered + redacted. */
@Component
public class AppActionExecutor implements NodeExecutor {

  private volatile PieceRegistry registry;
  private final SsrfGuard ssrf;
  private final ObjectMapper json = new ObjectMapper();

  public AppActionExecutor(SsrfGuard ssrf) {
    this.ssrf = ssrf;
  }

  @Override
  public String type() {
    return "app_action";
  }

  private PieceRegistry pieces() {
    if (registry == null) {
      synchronized (this) {
        if (registry == null) registry = PieceRegistry.loadAll();
      }
    }
    return registry;
  }

  @Override
  public NodeResult execute(ExecContext ctx) throws Exception {
    long start = System.currentTimeMillis();
    String app = String.valueOf(ctx.config().get("app"));
    String opId = String.valueOf(ctx.config().get("operation"));
    PieceManifest manifest = pieces().get(app);
    if (manifest == null || !manifest.enabled()) {
      return NodeResult.failure("Unknown or disabled app: " + app, System.currentTimeMillis() - start);
    }
    PieceManifest.Operation op = manifest.operations().stream()
        .filter(o -> o.id().equals(opId)).findFirst().orElse(null);
    if (op == null) {
      return NodeResult.failure("Unknown operation: " + opId, System.currentTimeMillis() - start);
    }

    List<String> usedSecrets = new ArrayList<>();
    String connection = null;
    String authType = manifest.auth() == null ? "none" : manifest.auth().type();
    if (!"none".equals(authType)) {
      Object connRef = ctx.config().get("connection");
      if (connRef == null || connRef.toString().isBlank()) {
        return NodeResult.failure("Connection is required", System.currentTimeMillis() - start);
      }
      connection = ctx.secret().apply(connRef.toString());
      usedSecrets.add(connection);
    }

    // config params overlay upstream refs (config wins)
    Map<String, Object> renderCtx = new LinkedHashMap<>(ctx.refContext());
    ctx.config().forEach((k, v) -> {
      if (v instanceof String || v instanceof Number || v instanceof Boolean) {
        renderCtx.putIfAbsent(k.toString(), v);
      }
    });
    if (connection != null) renderCtx.put("connection", connection);

    String base = op.baseUrl() != null ? op.baseUrl() : manifest.baseUrl();
    String url = renderTemplate(base, renderCtx);
    String path = renderTemplate(op.path(), renderCtx);
    String full = url + path;

    Map<String, String> headers = new LinkedHashMap<>();
    if (manifest.headers() != null) headers.putAll(manifest.headers());
    applyAuth(headers, full, manifest, connection);
    // query auth appends before render of extra params
    if (("query".equals(authType)) && connection != null) {
      full = appendQueryAuth(full, manifest, connection);
    }

    Object body = null;
    final String conn = connection;
    if (op.body() != null) {
      body = NodeHelpers.deepRender(op.body(), renderCtx, null, s -> conn, usedSecrets);
    } else if (op.method().equals("POST") || op.method().equals("PUT") || op.method().equals("PATCH")) {
      Object cfgBody = ctx.config().get("body");
      if (cfgBody != null) {
        body = NodeHelpers.deepRender(cfgBody, renderCtx, null, s -> conn, usedSecrets);
      }
    }

    ssrf.checkUrl(full);
    HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(full))
        .timeout(Duration.ofMillis(Math.min(30000, ctx.timeoutMs())));
    headers.forEach(builder::header);
    if (body != null) {
      String bodyStr = body instanceof String s ? s : NodeHelpers.toJson(body);
      builder.method(op.method(), HttpRequest.BodyPublishers.ofString(bodyStr));
    } else {
      builder.method(op.method(), HttpRequest.BodyPublishers.noBody());
    }
    HttpResponse<String> res = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("status", res.statusCode());
    String respBody = res.body() == null ? "" : res.body();
    try {
      out.put("body", json.readValue(respBody, Object.class));
    } catch (Exception e) {
      out.put("body", respBody);
    }
    out.put("app", app);
    out.put("operation", opId);
    return NodeResult.success(out, System.currentTimeMillis() - start);
  }

  private String renderTemplate(String template, Map<String, Object> ctx) {
    if (template == null) return "";
    // config/connection placeholders use the same {{ }} syntax
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("\\{\\{\\s*(.+?)\\s*\\}\\}").matcher(template);
    StringBuffer sb = new StringBuffer();
    while (m.find()) {
      Object v = new com.automationstudio.expression.RefResolver().resolve(m.group(1).trim(), ctx);
      m.appendReplacement(sb,
          java.util.regex.Matcher.quoteReplacement(v == null ? "" : v.toString()));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  private void applyAuth(
      Map<String, String> headers, String url, PieceManifest manifest, String connection) {
    if (connection == null) return;
    var auth = manifest.auth();
    String t = auth == null ? "none" : auth.type();
    switch (t) {
      case "bearer" -> headers.put("Authorization",
          (auth.prefix() == null ? "Bearer" : auth.prefix()) + " " + connection);
      case "header" -> headers.put(auth.header() == null ? "Authorization" : auth.header(),
          (auth.prefix() == null ? "" : auth.prefix()) + connection);
      case "basic" -> headers.put("Authorization", HttpExecutor.basic(
          connection.contains(":") ? connection.substring(0, connection.indexOf(':')) : connection,
          connection.contains(":") ? connection.substring(connection.indexOf(':') + 1) : ""));
      case "connectionString", "urlToken", "none", "query", "oauth2" -> { /* url/query handled elsewhere */ }
      default -> { /* apiKey treated as bearer by convention */ headers.put("Authorization", "Bearer " + connection); }
    }
    // apiKey type falls through to bearer convention
    if ("apiKey".equals(t)) headers.put("Authorization", "Bearer " + connection);
  }

  private String appendQueryAuth(String url, PieceManifest manifest, String connection) {
    var auth = manifest.auth();
    String sep = url.contains("?") ? "&" : "?";
    if (auth.queryParams() != null && !auth.queryParams().isEmpty()) {
      String[] parts = connection.split(":", 2);
      List<String> pairs = new ArrayList<>();
      for (int i = 0; i < auth.queryParams().size(); i++) {
        String v = i < parts.length ? parts[i] : "";
        pairs.add(URLEncoder.encode(auth.queryParams().get(i), StandardCharsets.UTF_8) + "="
            + URLEncoder.encode(v, StandardCharsets.UTF_8));
      }
      return url + sep + String.join("&", pairs);
    }
    String param = auth.queryParam() == null ? "api_key" : auth.queryParam();
    return url + sep + URLEncoder.encode(param, StandardCharsets.UTF_8) + "="
        + URLEncoder.encode(connection, StandardCharsets.UTF_8);
  }

  public static String basic(String user, String pass) {
    return "Basic " + Base64.getEncoder()
        .encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
  }
}
