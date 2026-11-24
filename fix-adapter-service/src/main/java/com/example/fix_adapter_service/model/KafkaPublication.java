package com.example.fix_adapter_service.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Entity
@Table(name="kafka_publication")
@Getter @Setter @NoArgsConstructor
public class KafkaPublication {
  @com.fasterxml.jackson.annotation.JsonIgnore
  @jakarta.persistence.Column(name = "trace_parent", length = 55)
  private String traceParent;

  @jakarta.persistence.PrePersist
  private void captureTraceParent() {
    traceParent = com.example.fix_adapter_service.observability.OutboxTrace.capture();
  }

    @Id
    @GeneratedValue(strategy=GenerationType.IDENTITY)
    @Column(name="id",nullable=false)
    private Long id;

    @Column(name="inbound_message_id",nullable=false,unique=true)
    private Long inboundMessageId;

    @Column(name="event_id",nullable=false,unique=true,length=120)
    private String eventId;

    @Column(name="topic",nullable=false,length=200)
    private String topic;

    @Column(name="partition_key",nullable=false,length=80)
    private String partitionKey;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(name="payload",nullable=false,columnDefinition="text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name="state",nullable=false,length=30)
    private PublicationState state;

    @Column(name="attempts",nullable=false)
    private int attempts;

    @Column(name="next_attempt_at",nullable=false)
    private Instant nextAttemptAt;

    @Column(name="created_at",nullable=false)
    private Instant createdAt;

    @Column(name="published_at")
    private Instant publishedAt;

    @Column(name="last_error",length=1000)
    private String lastError;

    @Version
    @Column(name="row_version",nullable=false)
    private Long rowVersion;

}
