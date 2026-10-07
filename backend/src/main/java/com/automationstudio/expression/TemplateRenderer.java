package com.automationstudio.expression;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders {{ref}} templates in string config fields. Objects/arrays insert as compact JSON.
 * Missing path -> empty string (caller logs a warning).
 */
public class TemplateRenderer {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*(.+?)\\s*\\}\\}");
  private static final ObjectMapper JSON = new ObjectMapper();

  private final RefResolver refs = new RefResolver();

  public record RenderResult(String text, List<String> missingRefs) {}

  public RenderResult render(String template, Map<String, Object> context) {
    if (template == null) return new RenderResult(null, List.of());
    Matcher m = PLACEHOLDER.matcher(template);
    StringBuilder sb = new StringBuilder();
    List<String> missing = new ArrayList<>();
    while (m.find()) {
      String ref = m.group(1);
      Object val = refs.resolve(ref, context);
      if (val == null) {
        missing.add(ref);
        m.appendReplacement(sb, "");
      } else if (val instanceof String s) {
        m.appendReplacement(sb, Matcher.quoteReplacement(s));
      } else if (val instanceof Number || val instanceof Boolean) {
        m.appendReplacement(sb, Matcher.quoteReplacement(val.toString()));
      } else {
        try {
          m.appendReplacement(sb, Matcher.quoteReplacement(JSON.writeValueAsString(val)));
        } catch (Exception e) {
          m.appendReplacement(sb, Matcher.quoteReplacement(val.toString()));
        }
      }
    }
    m.appendTail(sb);
    return new RenderResult(sb.toString(), List.copyOf(missing));
  }

  /** Is the whole value a bare reference (typed copy) vs a {{ }} template (string)? */
  public boolean isBareRef(String value) {
    return value != null && value.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*|\\[\\d+\\])*");
  }

  public static Set<String> templateRefs(String template) {
    Set<String> out = new LinkedHashSet<>();
    if (template == null) return out;
    Matcher m = PLACEHOLDER.matcher(template);
    while (m.find()) out.add(m.group(1).trim());
    return out;
  }
}
