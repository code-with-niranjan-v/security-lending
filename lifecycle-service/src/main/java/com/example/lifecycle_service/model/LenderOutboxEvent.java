package com.example.lifecycle_service.model;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;

@Entity
@Table(name = "outbox_event")
public class LenderOutboxEvent {
  @jakarta.persistence.Column(name = "trace_parent", length = 55)
  private String traceParent;

  @jakarta.persistence.PrePersist
  private void captureTraceParent() {
    traceParent = com.example.lifecycle_service.observability.OutboxTrace.capture();
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "organization_id")
  private Long organizationId;

  @Column(name = "event_id")
  private String eventId;

  private String topic;

  @Column(name = "partition_key")
  private String partitionKey;

  @Getter
  @Column(columnDefinition = "text")
  private String payload;

  private boolean published;

  @Column(name = "ws_sent")
  private boolean wsSent = true;

  private int attempts;

  @Column(name = "next_attempt_at")
  private Instant nextAttemptAt;

  @Column(name = "created_at")
  private Instant createdAt;

  protected LenderOutboxEvent() {}

  public LenderOutboxEvent(
      long organizationId, String eventId, String topic, String payload, Instant now) {
    this.organizationId = organizationId;
    this.eventId = eventId;
    this.topic = topic;
    this.partitionKey = Long.toString(organizationId);
    this.payload = payload;
    this.createdAt = now;
    this.nextAttemptAt = now;
  }
}
