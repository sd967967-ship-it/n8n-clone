package com.automationstudio.api;

import com.automationstudio.security.CredentialService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/credentials")
public class CredentialController {

  private final CredentialService service;

  public CredentialController(CredentialService service) {
    this.service = service;
  }

  @PostMapping
  public Map<String, Object> create(@RequestBody Map<String, Object> body) {
    UUID id = service.create(
        (String) body.get("name"), (String) body.get("provider"),
        (String) body.get("authType"), (String) body.get("app"),
        (String) body.get("value"));
    return Map.of("id", id);
  }

  @GetMapping
  public List<Map<String, Object>> list() {
    return service.list();
  }

  @DeleteMapping("/{id}")
  public void delete(@PathVariable UUID id) {
    service.delete(id);
  }
}
