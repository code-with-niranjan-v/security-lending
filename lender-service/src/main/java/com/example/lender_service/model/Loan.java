package com.example.lender_service.model;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "loan")
@Getter
@Setter
@NoArgsConstructor
public class Loan {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Long id;

  @Column(name = "organization_id", nullable = false)
  private Long organizationId;

  @Column(name = "connection_id", nullable = false)
  private Long connectionId;

  @Column(name = "msla_id", nullable = false)
  private Long mslaId;

  @Column(name = "reservation_id", nullable = false)
  private Long reservationId;

  @Column(name = "security_id", nullable = false)
  private Long securityId;

  @Column(name = "borrower_loan_id", nullable = false)
  private Long borrowerLoanId;

  @Column(name = "quantity", nullable = false)
  private Long quantity;

  @Column(name = "outstanding_quantity", nullable = false)
  private Long outstandingQuantity;

  @Column(name = "status", nullable = false, length = 40)
  private String status;

  @Column(name = "settlement_reference", length = 160)
  private String settlementReference;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "settled_at")
  private Instant settledAt;

  @Column(name = "fee_processed_through")
  private java.time.LocalDate feeProcessedThrough;

  @Column(name = "rebate_processed_through")
  private java.time.LocalDate rebateProcessedThrough;

  @Column(name = "fee_finalized", nullable = false)
  private boolean feeFinalized;

  @Column(name = "closed_at")
  private Instant closedAt;
}
