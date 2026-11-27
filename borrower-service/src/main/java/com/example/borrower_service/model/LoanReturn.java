package com.example.borrower_service.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Persistence model for loan_return; foreign keys are defined by Flyway. */
@Entity
@Table(name = "loan_return")
@Getter
@Setter
@NoArgsConstructor
public class LoanReturn {

  @Column(name = "failure_event_id", length = 120)
  private String failureEventId;

  @Column(name = "failure_reason", length = 1000)
  private String failureReason;

  @Column(name = "assets_released", nullable = false)
  private boolean assetsReleased;

  @Column(name = "replacement_return_id")
  private Long replacementReturnId;

  @Column(name = "recall_id")
  private Long recallId;

  @Column(name = "margin_call_id")
  private Long marginCallId;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Long id;

  @Column(name = "organization_id", nullable = false)
  private Long organizationId;

  @Column(name = "loan_id", nullable = false)
  private Long loanId;

  @Column(name = "quantity", nullable = false)
  private Long quantity;

  @Column(name = "owned_quantity", nullable = false)
  private Long ownedQuantity;

  @Column(name = "borrowed_quantity", nullable = false)
  private Long borrowedQuantity;

  @Column(name = "status", nullable = false, length = 30)
  private String status;

  @Column(name = "requested_at", nullable = false)
  private Instant requestedAt = Instant.now();

  @Column(name = "settled_at")
  private Instant settledAt;

  @Column(name = "owned_cost", nullable = false, precision = 24, scale = 6)
  private BigDecimal ownedCost = BigDecimal.ZERO;
}
