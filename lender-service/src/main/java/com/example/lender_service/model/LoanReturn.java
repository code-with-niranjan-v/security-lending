package com.example.lender_service.model;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "loan_return")
@Getter
@Setter
@NoArgsConstructor
public class LoanReturn {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Long id;

  @Column(name = "organization_id", nullable = false)
  private Long organizationId;

  @Column(name = "connection_id", nullable = false)
  private Long connectionId;

  @Column(name = "loan_id", nullable = false)
  private Long loanId;

  @Column(name = "borrower_return_id", nullable = false)
  private Long borrowerReturnId;

  @Column(name = "quantity", nullable = false)
  private Long quantity;

  @Column(name = "recall_id")
  private Long recallId;

  @Column(name = "margin_call_id")
  private Long marginCallId;

  @Column(name = "status", nullable = false, length = 40)
  private String status;

  @Column(name = "settlement_reference", length = 160)
  private String settlementReference;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "settled_at")
  private Instant settledAt;
}
