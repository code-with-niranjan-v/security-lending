package com.example.borrower_service.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Persistence model for outbox_event; foreign keys are defined by Flyway. */
@Entity
@Table(name = "outbox_event")
@Getter
@Setter
@NoArgsConstructor
public class OutboxEvent {
  @jakarta.persistence.Column(name = "trace_parent", length = 55)
  private String traceParent;

  @jakarta.persistence.PrePersist
  private void captureTraceParent() {
    traceParent = com.example.borrower_service.observability.OutboxTrace.capture();
  }

  @Column(name = "status", nullable = false, length = 30)
  private String status = "PENDING";

  @Column(name = "attempts", nullable = false)
  private Integer attempts = 0;

  @Column(name = "next_attempt_at", nullable = false)
  private Instant nextAttemptAt = Instant.now();

  @Column(name = "last_error", length = 1000)
  private String lastError;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Long id;

  @Column(name = "organization_id", nullable = false)
  private Long organizationId;

  @Column(name = "event_type", nullable = false, length = 80)
  private String eventType;

  @Column(name = "aggregate_id", nullable = false, length = 120)
  private String aggregateId;

  @com.fasterxml.jackson.annotation.JsonIgnore
  @Column(name = "payload", nullable = false, columnDefinition = "text")
  private String payload;

  @Column(name = "published", nullable = false)
  private Boolean published = false;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt = Instant.now();

  @Column(name = "ws_sent", nullable = false)
  private Boolean wsSent = false;
}
