package com.automationstudio.pieces;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Loads every {@code pieces/*.yml} manifest from the classpath.
 * Tiers without usable auth are skipped at runtime; disabled (oauth2) manifests
 * stay visible to the UI as "coming soon".
 */
public class PieceRegistry {

  private static final Set<String> AUTH_TYPES =
      Set.of("none", "apiKey", "bearer", "basic", "header", "query", "urlToken",
          "connectionString", "oauth2");
  private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

  private final Map<String, PieceManifest> pieces = new LinkedHashMap<>();

  public static PieceRegistry loadAll() {
    PieceRegistry registry = new PieceRegistry();
    try {
      PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
      Resource[] resources = resolver.getResources("classpath:pieces/*.yml");
      ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
      for (Resource resource : resources) {
        try (InputStream in = resource.getInputStream()) {
          PieceManifest manifest = yaml.readValue(in, PieceManifest.class);
          List<String> problems = validate(manifest, resource.getFilename());
          if (!problems.isEmpty()) {
            throw new IllegalStateException(
                "Invalid piece " + resource.getFilename() + ": " + problems);
          }
          if (registry.pieces.containsKey(manifest.app())) {
            throw new IllegalStateException("Duplicate piece app: " + manifest.app());
          }
          registry.pieces.put(manifest.app(), manifest);
        }
      }
    } catch (Exception e) {
      throw new IllegalStateException("Failed to load piece manifests", e);
    }
    return registry;
  }

  /** Schema check for one manifest; returns human-readable problems (empty = valid). */
  public static List<String> validate(PieceManifest manifest, String file) {
    List<String> problems = new ArrayList<>();
    if (manifest == null) {
      problems.add(file + ": empty manifest");
      return problems;
    }
    if (manifest.app() == null || !manifest.app().matches("[a-z0-9_]+")) {
      problems.add("app must match [a-z0-9_]+");
    }
    if (manifest.displayName() == null || manifest.displayName().isBlank()) {
      problems.add("displayName is required");
    }
    if (manifest.category() == null || manifest.category().isBlank()) {
      problems.add("category is required");
    }
    if (manifest.auth() == null || !AUTH_TYPES.contains(manifest.auth().type())) {
      problems.add("auth.type must be one of " + AUTH_TYPES);
    }
    if (manifest.baseUrl() == null || manifest.baseUrl().isBlank()) {
      problems.add("baseUrl is required");
    }
    if (manifest.operations() == null || manifest.operations().isEmpty()) {
      problems.add("at least one operation is required");
    } else {
      Set<String> ids = new java.util.HashSet<>();
      for (PieceManifest.Operation op : manifest.operations()) {
        if (op.id() == null || !ids.add(op.id())) problems.add("duplicate/blank operation id");
        if (op.method() == null || !METHODS.contains(op.method())) {
          problems.add("operation " + op.id() + ": bad method");
        }
        if (op.path() == null || !op.path().startsWith("/")) {
          problems.add("operation " + op.id() + ": path must start with /");
        }
      }
    }
    if (manifest.triggers() != null) {
      for (PieceManifest.Trigger t : manifest.triggers()) {
        if (t.method() != null && !METHODS.contains(t.method())) {
          problems.add("trigger " + t.id() + ": bad method");
        }
        if (t.path() == null || !t.path().startsWith("/")) {
          problems.add("trigger " + t.id() + ": path must start with /");
        }
      }
    }
    return problems;
  }

  public Map<String, PieceManifest> all() {
    return Map.copyOf(pieces);
  }

  public PieceManifest get(String app) {
    return pieces.get(app);
  }

  public int size() {
    return pieces.size();
  }
}
