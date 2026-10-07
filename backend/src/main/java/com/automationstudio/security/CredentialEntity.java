package com.automationstudio.security;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "credentials")
public class CredentialEntity {
  @Id
  public UUID id;
  @Column(nullable = false, unique = true)
  public String name;
  @Column(nullable = false)
  public String provider = "custom";
  @Column(name = "auth_type", nullable = false)
  public String authType = "generic";
  @Column(nullable = false)
  public String app = "";
  @Column(name = "encrypted_value", nullable = false)
  public byte[] encryptedValue;
  @Column(nullable = false)
  public byte[] nonce;
  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  @PrePersist
  public void prePersist() {
    if (id == null) id = UUID.randomUUID();
    if (createdAt == null) createdAt = Instant.now();
  }
}
