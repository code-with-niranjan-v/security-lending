package com.example.lender_service.model;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
    traceParent = com.example.lender_service.observability.OutboxTrace.capture();
  }

  @Column(name = "status", nullable = false, length = 30)
  private String status = "PENDING";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Long id;

  @Column(name = "organization_id", nullable = false)
  private Long organizationId;

  @Column(name = "event_id", nullable = false, length = 120)
  private String eventId;

  @Column(name = "topic", nullable = false, length = 200)
  private String topic;

  @Column(name = "partition_key", nullable = false, length = 80)
  private String partitionKey;

  @com.fasterxml.jackson.annotation.JsonIgnore
  @Column(name = "payload", nullable = false, columnDefinition = "text")
  private String payload;

  @Column(name = "published", nullable = false)
  private Boolean published;

  @Column(name = "ws_sent", nullable = false)
  private Boolean wsSent;

  @Column(name = "attempts", nullable = false)
  private Integer attempts;

  @Column(name = "next_attempt_at", nullable = false)
  private Instant nextAttemptAt;

  @Column(name = "last_error", length = 1000)
  private String lastError;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;
}
