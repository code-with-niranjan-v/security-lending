package com.example.fix_adapter_service.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Entity
@Table(name="outbound_message")
@Getter @Setter @NoArgsConstructor
public class OutboundMessage {
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(name="trace_parent",length=55)
    private String traceParent;

    @PrePersist
    private void captureTraceParent() {
        traceParent = com.example.fix_adapter_service.observability.OutboxTrace.capture();
    }

    @Id
    @GeneratedValue(strategy=GenerationType.IDENTITY)
    @Column(name="id",nullable=false)
    private Long id;

    @Column(name="event_id",nullable=false,unique=true,length=120)
    private String eventId;

    @Column(name="event_type",nullable=false,length=80)
    private String eventType;

    @Column(name="organization_id",nullable=false)
    private Long organizationId;

    @Column(name="reference_id",nullable=false)
    private Long referenceId;

    @Enumerated(EnumType.STRING)
    @Column(name="message_kind",nullable=false,length=20)
    private MessageKind messageKind;

    @Column(name="session_key",nullable=false,length=240)
    private String sessionKey;

    @Column(name="counterparty_id",nullable=false,length=100)
    private String counterpartyId;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(name="payload",nullable=false,columnDefinition="text")
    private String payload;

    @Column(name="payload_hash",nullable=false,length=64)
    private String payloadHash;

    @Enumerated(EnumType.STRING)
    @Column(name="state",nullable=false,length=30)
    private DeliveryState state;

    @Column(name="attempts",nullable=false)
    private int attempts;

    @Column(name="next_attempt_at",nullable=false)
    private Instant nextAttemptAt;

    @Column(name="created_at",nullable=false)
    private Instant createdAt;

    @Column(name="last_sent_at")
    private Instant lastSentAt;

    @Column(name="acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name="last_error",length=1000)
    private String lastError;

    @Column(name="source_topic",nullable=false,length=200)
    private String sourceTopic;

    @Column(name="source_partition",nullable=false)
    private int sourcePartition;

    @Column(name="source_offset",nullable=false)
    private long sourceOffset;

    @Version
    @Column(name="row_version",nullable=false)
    private Long rowVersion;

}
