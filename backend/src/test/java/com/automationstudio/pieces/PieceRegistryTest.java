package com.automationstudio.pieces;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class PieceRegistryTest {

  @Test
  void allManifestsLoadAndValidate() {
    PieceRegistry registry = PieceRegistry.loadAll();
    assertTrue(registry.size() >= 80, "expected 80+ pieces, got " + registry.size());
    for (PieceManifest m : registry.all().values()) {
      assertTrue(PieceRegistry.validate(m, m.app()).isEmpty(), m.app());
    }
  }

  @Test
  void spotCheckKnownApps() {
    PieceRegistry registry = PieceRegistry.loadAll();
    assertNotNull(registry.get("github"));
    assertTrue(registry.get("github").enabled());
    assertTrue(registry.get("github").operations().stream().anyMatch(o -> o.id().equals("list_issues")));
    assertTrue(registry.get("telegram").triggers().stream().anyMatch(t -> t.id().equals("updates")));
    // OAuth-only ships disabled
    assertNotNull(registry.get("gmail"));
    assertFalse(registry.get("gmail").enabled());
  }

  @Test
  void manifestsContainNoSecrets() throws Exception {
    Path dir = Paths.get("src", "main", "resources", "pieces");
    assertTrue(Files.isDirectory(dir));
    List<String> markers = List.of("sk-", "xoxb-", "ghp_", "glpat-", "BEGIN PRIVATE");
    try (Stream<Path> files = Files.list(dir)) {
      for (Path f : files.filter(p -> p.toString().endsWith(".yml")).toList()) {
        String content = Files.readString(f);
        for (String marker : markers) {
          assertFalse(content.contains(marker), f.getFileName() + " contains " + marker);
        }
      }
    }
  }
}
