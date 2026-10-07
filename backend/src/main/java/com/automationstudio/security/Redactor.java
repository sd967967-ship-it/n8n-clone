package com.automationstudio.security;

import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Redacts secrets before persisting inputs/logs. Credential fields stored as templates. */
@Component
public class Redactor {

  private static final Set<String> SECRET_HEADERS =
      Set.of("authorization", "cookie", "x-api-key", "proxy-authorization");
  private static final Pattern CREDENTIAL_VALUE = Pattern.compile("\\{\\{credentials\\.[^}]+\\}\\}");

  public String maskHeader(String name, String value) {
    if (name != null && SECRET_HEADERS.contains(name.toLowerCase())) return "***";
    if (value != null && CREDENTIAL_VALUE.matcher(value).find()) return value; // template, safe
    return value;
  }

  /** Replace any pasted secret-looking value with its template form is caller-side; here: mask. */
  public String maskValue(String value, boolean isSecretField) {
    if (isSecretField) return "***";
    return value;
  }
}
