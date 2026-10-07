package com.automationstudio.security;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CredentialService {

  private final CredentialRepository repository;
  private final SecretsService secrets;

  public CredentialService(CredentialRepository repository, SecretsService secrets) {
    this.repository = repository;
    this.secrets = secrets;
  }

  /** Values are write-only: list returns names only, never values. */
  public List<java.util.Map<String, Object>> list() {
    return repository.findAll().stream()
        .map(c -> java.util.Map.<String, Object>of(
            "id", c.id, "name", c.name, "provider", c.provider,
            "authType", c.authType, "app", c.app))
        .toList();
  }

  @Transactional
  public UUID create(String name, String provider, String authType, String app, String value) {
    if (name == null || name.isBlank() || value == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name and value required");
    }
    if (repository.findByName(name).isPresent()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Credential exists: " + name);
    }
    SecretsService.Sealed sealed = secrets.seal(value);
    CredentialEntity c = new CredentialEntity();
    c.name = name;
    c.provider = provider == null ? "custom" : provider;
    c.authType = authType == null ? "generic" : authType;
    c.app = app == null ? "" : app;
    c.encryptedValue = sealed.ciphertext();
    c.nonce = sealed.nonce();
    return repository.save(c).id;
  }

  @Transactional
  public void delete(UUID id) {
    repository.delete(repository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found")));
  }

  /** Resolve at call time, in memory. Never persisted, never logged. */
  public String resolve(String name) {
    CredentialEntity c = repository.findByName(name)
        .orElseThrow(() -> new IllegalArgumentException("Unknown credential: " + name));
    return secrets.open(c.encryptedValue, c.nonce);
  }
}
