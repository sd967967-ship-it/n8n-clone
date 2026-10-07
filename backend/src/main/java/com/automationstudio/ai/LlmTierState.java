package com.automationstudio.ai;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "llm_tier_state")
public class LlmTierState {
  @Id
  @Column(name = "tier_id")
  public String tierId;
  @Column(nullable = false)
  public String state = "AVAILABLE";
  public Instant until;
  @Column(name = "used_today", nullable = false)
  public int usedToday;
  @Column(name = "day_utc", nullable = false)
  public LocalDate dayUtc = LocalDate.now(java.time.ZoneOffset.UTC);
}
