package com.automationstudio.nodes;

import com.automationstudio.execution.ExecContext;
import com.automationstudio.execution.NodeExecutor;
import com.automationstudio.execution.NodeResult;
import com.automationstudio.security.Redactor;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class HttpExecutor implements NodeExecutor {

  private final SsrfGuard ssrf;
  private final Redactor redactor;
  private final ObjectMapper json = new ObjectMapper();
  private final long connectTimeoutMs;
  private final long readTimeoutMs;

  public HttpExecutor(
      SsrfGuard ssrf,
      Redactor redactor,
      @Value("${http.connect-timeout-ms:10000}") long connectTimeoutMs,
      @Value("${http.read-timeout-ms:30000}") long readTimeoutMs) {
    this.ssrf = ssrf;
    this.redactor = redactor;
    this.connectTimeoutMs = connectTimeoutMs;
    this.readTimeoutMs = readTimeoutMs;
  }

  @Override
  public String type() {
    return "http_request";
  }

  @Override
  @SuppressWarnings("unchecked")
  public NodeResult execute(ExecContext ctx) throws Exception {
    long start = System.currentTimeMillis();
    List<String> usedSecrets = new ArrayList<>();
    String method = String.valueOf(ctx.config().getOrDefault("method", "GET")).toUpperCase();
    String url = NodeHelpers.render(String.valueOf(ctx.config().get("url")),
        ctx.refContext(), ctx.config(), ctx.secret(), usedSecrets);
    Map<String, Object> headersCfg = ctx.config().get("headers") instanceof Map
        ? (Map<String, Object>) ctx.config().get("headers") : Map.of();
    Map<String, Object> queryCfg = ctx.config().get("query") instanceof Map
        ? (Map<String, Object>) ctx.config().get("query") : Map.of();

    StringBuilder full = new StringBuilder(url);
    if (!queryCfg.isEmpty()) {
      full.append(url.contains("?") ? "&" : "?");
      List<String> parts = new ArrayList<>();
      for (var e : queryCfg.entrySet()) {
        Object rendered = NodeHelpers.deepRender(e.getValue(), ctx.refContext(),
            ctx.config(), ctx.secret(), usedSecrets);
        parts.add(URLEncoder.encode(e.getKey().toString(), StandardCharsets.UTF_8) + "="
            + URLEncoder.encode(String.valueOf(rendered), StandardCharsets.UTF_8));
      }
      full.append(String.join("&", parts));
    }

    HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(connectTimeoutMs))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    Object bodyCfg = NodeHelpers.deepRender(ctx.config().get("body"), ctx.refContext(),
        ctx.config(), ctx.secret(), usedSecrets);
    String current = full.toString();
    HttpResponse<String> res = null;
    for (int redirect = 0; redirect <= 5; redirect++) {
      ssrf.checkUrl(current); // DNS re-check on every hop
      HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(current))
          .timeout(Duration.ofMillis(Math.min(readTimeoutMs, ctx.timeoutMs())));
      for (var e : headersCfg.entrySet()) {
        String rendered = String.valueOf(NodeHelpers.deepRender(e.getValue(), ctx.refContext(),
            ctx.config(), ctx.secret(), usedSecrets));
        builder.header(e.getKey().toString(), rendered);
      }
      if (bodyCfg != null && (method.equals("POST") || method.equals("PUT") || method.equals("PATCH"))) {
        String bodyStr = bodyCfg instanceof String s ? s : NodeHelpers.toJson(bodyCfg);
        builder.method(method, HttpRequest.BodyPublishers.ofString(bodyStr));
      } else if (method.equals("GET") || method.equals("DELETE")) {
        builder.method(method, HttpRequest.BodyPublishers.noBody());
      } else {
        builder.method(method, HttpRequest.BodyPublishers.noBody());
      }
      res = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      int code = res.statusCode();
      if (code >= 300 && code < 400) {
        String loc = res.headers().firstValue("location").orElse(null);
        if (loc == null) break;
        current = URI.create(current).resolve(loc).toString();
        continue;
      }
      break;
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("status", res.statusCode());
    Map<String, Object> resHeaders = new LinkedHashMap<>();
    res.headers().map().forEach((k, v) ->
        resHeaders.put(k, redactor.maskHeader(k, String.join(",", v))));
    out.put("headers", resHeaders);
    String body = res.body() == null ? "" : res.body();
    try {
      out.put("body", json.readValue(body, Object.class));
    } catch (Exception e) {
      out.put("body", body);
    }
    return NodeResult.success(out, System.currentTimeMillis() - start);
  }

  public static String basic(String user, String pass) {
    return "Basic " + Base64.getEncoder()
        .encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
  }
}
