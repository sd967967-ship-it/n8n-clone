package com.automationstudio.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CredentialRepository extends JpaRepository<CredentialEntity, UUID> {
  Optional<CredentialEntity> findByName(String name);
}
