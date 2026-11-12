package com.example.lifecycle_service.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "security_master")
@Getter
public class FeeSecurity {
  @Id
  private Long id;

  private String symbol;
  private String exchange;
  private String currency;
  private BigDecimal price;

  @Column(name = "price_at")
  private Instant priceAt;

  protected FeeSecurity() {}
}
