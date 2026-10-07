package com.automationstudio.nodes;

import com.automationstudio.expression.RefResolver;
import com.automationstudio.expression.TemplateRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Shared rendering: upstream refs + node-config params, secrets resolved on demand. */
public class NodeHelpers {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TemplateRenderer RENDERER = new TemplateRenderer();
  private static final RefResolver REFS = new RefResolver();

  /** Render a template: {{config keys}} first, then upstream refs, then {{credentials.X}}. */
  public static String render(
      String template, Map<String, Object> refCtx, Map<String, Object> config,
      Function<String, String> secret, List<String> usedSecrets) {
    if (template == null) return null;
    Map<String, Object> ctx = new LinkedHashMap<>(refCtx);
    if (config != null) {
      for (var e : config.entrySet()) {
        if (e.getValue() instanceof String || e.getValue() instanceof Number
            || e.getValue() instanceof Boolean) {
          ctx.putIfAbsent(e.getKey().toString(), e.getValue());
        }
      }
    }
    // resolve only the credentials actually referenced
    Map<String, Object> creds = new LinkedHashMap<>();
    for (String ref : TemplateRenderer.templateRefs(template)) {
      if (ref.equals("credentials") || ref.startsWith("credentials.")) {
        String name = ref.substring("credentials.".length()).split("[.\\[]")[0];
        if (!creds.containsKey(name)) {
          String value = secret.apply(name);
          creds.put(name, value);
          if (usedSecrets != null) usedSecrets.add(value);
        }
      }
    }
    if (!creds.isEmpty()) ctx.put("credentials", creds);
    TemplateRenderer.RenderResult r = RENDERER.render(template, ctx);
    return r.text();
  }

  @SuppressWarnings("unchecked")
  public static Object deepRender(
      Object value, Map<String, Object> refCtx, Map<String, Object> config,
      Function<String, String> secret, List<String> usedSecrets) {
    if (value instanceof String s) return render(s, refCtx, config, secret, usedSecrets);
    if (value instanceof Map<?, ?> m) {
      Map<String, Object> out = new LinkedHashMap<>();
      for (var e : m.entrySet()) {
        out.put(e.getKey().toString(), deepRender(e.getValue(), refCtx, config, secret, usedSecrets));
      }
      return out;
    }
    if (value instanceof List<?> l) {
      List<Object> out = new ArrayList<>();
      for (Object el : l) out.add(deepRender(el, refCtx, config, secret, usedSecrets));
      return out;
    }
    return value;
  }

  public static Object resolveBareRef(String ref, Map<String, Object> refCtx) {
    return REFS.resolve(ref, refCtx);
  }

  public static String toJson(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (Exception e) {
      return String.valueOf(value);
    }
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> toMap(Object value) {
    if (value instanceof Map<?, ?> m) {
      Map<String, Object> out = new LinkedHashMap<>();
      m.forEach((k, v) -> out.put(k.toString(), v));
      return out;
    }
    return Map.of("value", value);
  }

  /** Replace secret values with *** before persisting inputs/outputs. */
  public static String redactSecrets(String json, List<String> secrets) {
    String out = json;
    for (String s : secrets) {
      if (s != null && !s.isBlank() && out.contains(s)) {
        out = out.replace(s, "***");
      }
    }
    return out;
  }

  public static long longOf(Object v, long def) {
    if (v instanceof Number n) return n.longValue();
    try {
      return Long.parseLong(String.valueOf(v));
    } catch (Exception e) {
      return def;
    }
  }

  public static int intOf(Object v, int def) {
    return (int) longOf(v, def);
  }

  public static Set<String> templateRefs(String template) {
    return TemplateRenderer.templateRefs(template);
  }
}
