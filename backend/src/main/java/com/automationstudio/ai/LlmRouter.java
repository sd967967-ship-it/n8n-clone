package com.automationstudio.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * Routing chain: OpenRouter Qwen first, other :free alts, then local Ollama.
 * Restarts from the top per call (returns to Qwen on reset). Response cache,
 * per-tier COOLDOWN/EXHAUSTED/CIRCUIT states persisted in llm_tier_state.
 */
@Component
public class LlmRouter {

  public record LlmAnswer(
      String text, Object json, String model, String tier, String provider,
      List<Map<String, String>> attempts, Map<String, Object> usage) {}

  public record TierStatus(
      String id, String state, String nextReset, int usedToday, Integer remaining) {}

  private record Tier(
      String id, String provider, String model, int priority, String quotaScope,
      List<String> capabilities, int contextTokens) {}

  private final List<Tier> tiers;
  private final Map<String, String> providerBaseUrl;
  private final Map<String, String> providerKeyEnv;
  private final int maxAttempts;
  private final long timeoutMs;
  private final LlmTierStateRepository stateRepo;
  private final ObjectMapper json = new ObjectMapper();
  private final HttpClient http;
  private final Map<String, LlmAnswer> cache =
      new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, LlmAnswer> e) {
          return size() > 500;
        }
      };
  private final ConcurrentHashMap<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();

  @SuppressWarnings("unchecked")
  public LlmRouter(
      LlmTierStateRepository stateRepo,
      @Value("${llm.timeout-ms:180000}") long timeoutMs,
      @Value("${llm.max-attempts:5}") int maxAttempts) {
    this.stateRepo = stateRepo;
    this.timeoutMs = timeoutMs;
    this.maxAttempts = maxAttempts;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    try {
      Map<String, Object> cfg;
      try (var in = new ClassPathResource("llm-routing.yml").getInputStream()) {
        cfg = new Yaml().load(in);
      }
      List<Map<String, Object>> tierCfgs = (List<Map<String, Object>>) cfg.get("tiers");
      List<Tier> list = new ArrayList<>();
      for (Map<String, Object> t : tierCfgs) {
        list.add(new Tier(
            (String) t.get("id"), (String) t.get("provider"), (String) t.get("model"),
            (int) t.getOrDefault("priority", 100), (String) t.getOrDefault("quotaScope", "model"),
            (List<String>) t.getOrDefault("capabilities", List.of()),
            (int) t.getOrDefault("contextTokens", 8192)));
      }
      list.sort((a, b) -> Integer.compare(a.priority(), b.priority()));
      this.tiers = List.copyOf(list);
      Map<String, Object> providers = (Map<String, Object>) cfg.get("providers");
      Map<String, String> base = new LinkedHashMap<>();
      Map<String, String> keys = new LinkedHashMap<>();
      for (var e : providers.entrySet()) {
        Map<String, Object> p = (Map<String, Object>) e.getValue();
        base.put(e.getKey(), resolvePlaceholders((String) p.get("baseUrl")));
        keys.put(e.getKey(), (String) p.getOrDefault("apiKeyEnv", ""));
      }
      this.providerBaseUrl = Map.copyOf(base);
      this.providerKeyEnv = Map.copyOf(keys);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to load llm-routing.yml", e);
    }
  }

  public List<TierStatus> status() {
    List<TierStatus> out = new ArrayList<>();
    for (Tier t : tiers) {
      LlmTierState s = loadState(t.id());
      String keyEnv = providerKeyEnv.getOrDefault(t.provider(), "");
      String key = keyEnv.isEmpty() ? "" : System.getenv(keyEnv);
      String effective = (key == null || key.isBlank()) && !t.provider().equals("ollama")
          ? "NO_KEY (" + s.state + ")" : s.state;
      out.add(new TierStatus(t.id(), effective,
          s.until == null ? null : s.until.toString(), s.usedToday, null));
    }
    return out;
  }

  /** Local-only routing for private prompts (skips every hosted tier). */
  public LlmAnswer chat(String prompt, String system, boolean jsonMode, String routing) {
    return chat(prompt, system, jsonMode, "local_only".equals(routing));
  }

  public synchronized LlmAnswer chat(
      String prompt, String system, boolean jsonMode, boolean localOnly) {
    String cacheKey = sha(prompt + "\n" + system + "\n" + jsonMode);
    LlmAnswer cached;
    synchronized (cache) {
      cached = cache.get(cacheKey);
    }
    if (cached != null) return cached;
    List<Map<String, String>> attempts = new ArrayList<>();
    int used = 0;
    for (Tier tier : tiers) {
      if (used >= maxAttempts) break;
      boolean hosted = !tier.provider().equals("ollama");
      if (localOnly && hosted) continue;
      LlmTierState state = loadState(tier.id());
      if (!isAvailable(state)) {
        attempts.add(Map.of("tier", tier.id(), "result", state.state));
        continue;
      }
      String keyEnv = providerKeyEnv.getOrDefault(tier.provider(), "");
      String key = keyEnv.isEmpty() ? "" : System.getenv(keyEnv);
      if (hosted && (key == null || key.isBlank())) {
        attempts.add(Map.of("tier", tier.id(), "result", "NO_KEY"));
        continue;
      }
      if (jsonMode && !tier.capabilities().contains("json")) {
        attempts.add(Map.of("tier", tier.id(), "result", "NO_JSON_CAPABILITY"));
        continue;
      }
      used++;
      try {
        LlmAnswer answer = callTier(tier, key, prompt, system, jsonMode);
        markSuccess(tier);
        answer.attempts().addAll(attempts);
        synchronized (cache) {
          cache.put(cacheKey, answer);
        }
        return answer;
      } catch (TierException e) {
        attempts.add(Map.of("tier", tier.id(), "result", e.kind()));
        applyFailure(tier, e);
      } catch (Exception e) {
        attempts.add(Map.of("tier", tier.id(), "result", "ERROR"));
        applyFailure(tier, new TierException("ERROR", e.getMessage()));
      }
    }
    StringBuilder msg = new StringBuilder("LLM_ALL_TIERS_EXHAUSTED. Attempts: ");
    for (var a : attempts) msg.append(a).append("; ");
    throw new IllegalStateException(msg.toString());
  }

  // ---- per-tier calls ----

  private LlmAnswer callTier(Tier tier, String key, String prompt, String system, boolean jsonMode)
      throws Exception {
    if (tier.provider().equals("ollama")) {
      return callOllama(tier, prompt, system, jsonMode);
    }
    return callOpenAiCompatible(tier, key, prompt, system, jsonMode);
  }

  private LlmAnswer callOpenAiCompatible(
      Tier tier, String key, String prompt, String system, boolean jsonMode) throws Exception {
    String base = providerBaseUrl.get(tier.provider());
    List<Map<String, String>> messages = new ArrayList<>();
    if (system != null && !system.isBlank()) messages.add(Map.of("role", "system", "content", system));
    messages.add(Map.of("role", "user", "content", prompt));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", tier.model());
    body.put("messages", messages);
    if (jsonMode) body.put("response_format", Map.of("type", "json_object"));
    HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
        .timeout(Duration.ofMillis(timeoutMs))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + key)
        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
        .build();
    HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
    int code = res.statusCode();
    if (code == 429) {
      String text = res.body() == null ? "" : res.body().toLowerCase();
      boolean quota = text.contains("quota") || text.contains("daily") || text.contains("limit")
          || text.contains("insufficient");
      throw new TierException(quota ? "QUOTA" : "RATE_LIMIT", res.body());
    }
    if (code >= 500) throw new TierException("UPSTREAM_5XX", "HTTP " + code);
    if (code != 200) throw new TierException("UPSTREAM_" + code, res.body());
    JsonNode root = json.readTree(res.body());
    JsonNode choices = root.path("choices");
    if (!choices.isArray() || choices.isEmpty()) throw new TierException("BAD_RESPONSE", res.body());
    String text = choices.get(0).path("message").path("content").asText("");
    Object parsed = null;
    if (jsonMode) {
      parsed = parseJsonOrThrow(stripFences(text));
    }
    Map<String, Object> usage = new LinkedHashMap<>();
    JsonNode u = root.path("usage");
    if (u.isObject()) {
      u.fields().forEachRemaining(e -> usage.put(e.getKey(), e.getValue().asText()));
    }
    return new LlmAnswer(text, parsed, tier.model(), tier.id(), tier.provider(),
        new ArrayList<>(), usage);
  }

  private LlmAnswer callOllama(Tier tier, String prompt, String system, boolean jsonMode)
      throws Exception {
    String base = providerBaseUrl.getOrDefault("ollama", "http://127.0.0.1:11434");
    String model = System.getenv("OLLAMA_MODEL");
    if (model == null || model.isBlank()) model = tier.model();
    String full = (system == null || system.isBlank() ? "" : system + "\n\n") + prompt;
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", model);
    body.put("prompt", full);
    body.put("stream", false);
    if (jsonMode) body.put("format", "json");
    HttpRequest req;
    try {
      req = HttpRequest.newBuilder(URI.create(base + "/api/generate"))
          .timeout(Duration.ofMillis(timeoutMs))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
          .build();
    } catch (IllegalArgumentException e) {
      throw new TierException("UNREACHABLE", e.getMessage());
    }
    HttpResponse<String> res;
    try {
      res = http.send(req, HttpResponse.BodyHandlers.ofString());
    } catch (java.net.http.HttpTimeoutException e) {
      throw new TierException("TIMEOUT", e.getMessage());
    } catch (java.io.IOException e) {
      throw new TierException("UNREACHABLE", e.getMessage());
    }
    if (res.statusCode() != 200) throw new TierException("UPSTREAM_" + res.statusCode(), res.body());
    String text = json.readTree(res.body()).path("response").asText("");
    Object parsed = jsonMode ? parseJsonOrThrow(stripFences(text)) : null;
    return new LlmAnswer(text, parsed, model, tier.id(), "ollama", new ArrayList<>(), Map.of());
  }

  private Object parseJsonOrThrow(String text) throws TierException {
    try {
      return json.readValue(text, Object.class);
    } catch (Exception e) {
      throw new TierException("INVALID_JSON", text);
    }
  }

  private static String stripFences(String text) {
    if (text == null) return "";
    String t = text.trim();
    if (t.startsWith("```")) {
      int nl = t.indexOf('\n');
      t = nl < 0 ? "" : t.substring(nl + 1);
      int end = t.lastIndexOf("```");
      if (end >= 0) t = t.substring(0, end);
    }
    return t.trim();
  }

  // ---- state machine ----

  private LlmTierState loadState(String tierId) {
    return stateRepo.findById(tierId).orElseGet(() -> {
      LlmTierState s = new LlmTierState();
      s.tierId = tierId;
      return s;
    });
  }

  private boolean isAvailable(LlmTierState s) {
    if (s.until != null && s.until.isAfter(Instant.now())) return false;
    if ("DISABLED".equals(s.state)) return false;
    return true;
  }

  private void markSuccess(Tier tier) {
    consecutiveFailures.remove(tier.id());
    LlmTierState s = loadState(tier.id());
    if ("CIRCUIT_OPEN".equals(s.state) && s.until != null && s.until.isAfter(Instant.now())) {
      stateRepo.save(s); // probe ok -> close circuit
      s.state = "AVAILABLE";
      s.until = null;
    } else {
      s.state = "AVAILABLE";
      s.until = null;
    }
    stateRepo.save(s);
  }

  private void applyFailure(Tier tier, TierException e) {
    LlmTierState s = loadState(tier.id());
    switch (e.kind()) {
      case "RATE_LIMIT" -> {
        s.state = "COOLDOWN";
        s.until = Instant.now().plusSeconds(60);
      }
      case "QUOTA" -> {
        s.state = "EXHAUSTED";
        // provider-wide for OpenRouter (shared account cap)
        if ("provider".equals(tier.quotaScope())) {
          for (Tier sibling : tiers) {
            if (sibling.provider().equals(tier.provider())) {
              LlmTierState ss = loadState(sibling.id());
              ss.state = "EXHAUSTED";
              ss.until = nextUtcMidnight();
              stateRepo.save(ss);
            }
          }
          return;
        }
        s.until = nextUtcMidnight();
      }
      case "UPSTREAM_5XX", "TIMEOUT", "UNREACHABLE" -> {
        int n = consecutiveFailures.merge(tier.id(), 1, Integer::sum);
        if (n >= 3) {
          consecutiveFailures.remove(tier.id());
          s.state = "CIRCUIT_OPEN";
          s.until = Instant.now().plusSeconds(15 * 60);
          stateRepo.save(s);
        }
        return;
      }
      default -> {
        // INVALID_JSON / BAD_RESPONSE: soft-fail, tier stays available
        stateRepo.save(s);
        return;
      }
    }
    stateRepo.save(s);
  }

  private static Instant nextUtcMidnight() {
    return java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
        .toLocalDate().plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
  }

  private static String resolvePlaceholders(String value) {
    if (value == null) return "";
    String out = value;
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("\\$\\{([^:}]+)(:-([^}]*))?\\}").matcher(value);
    StringBuffer sb = new StringBuffer();
    while (m.find()) {
      String env = System.getenv(m.group(1));
      String rep = (env == null || env.isBlank())
          ? (m.group(3) == null ? "" : m.group(3)) : env;
      m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(rep));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  private static String sha(String s) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      return Integer.toHexString(s.hashCode());
    }
  }

  public static class TierException extends Exception {
    private final String kind;

    public TierException(String kind, String message) {
      super(message);
      this.kind = kind;
    }

    public String kind() {
      return kind;
    }
  }
}
