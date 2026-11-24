package com.example.fix_adapter_service.service;

import com.example.fix_adapter_service.observability.OutboxTrace;
import org.slf4j.LoggerFactory;
import com.example.fix_adapter_service.config.AdapterProperties;
import com.example.fix_adapter_service.model.PublicationState;
import com.example.fix_adapter_service.repository.KafkaPublicationRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PublicationService {
  private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(PublicationService.class);

  private final KafkaPublicationRepository publications;
  private final BusinessEventPublisher publisher;
  private final com.example.fix_adapter_service.repository.InboundMessageRepository inbound;
  private final AdapterProperties properties;
  private final Clock clock;

  @Transactional
  public void publishNext(String partitionKey) {
    if (!properties.isKafkaEnabled()) return;
    var pending = publications.lockHead(partitionKey, PageRequest.of(0, 1));
    if (pending.isEmpty()) return;
    var publication = pending.get(0);
    var now = clock.instant();
    if (
      java.util.Set.of(
        PublicationState.SUSPENDED,
        PublicationState.FAILED,
        PublicationState.CLOSED
      ).contains(publication.getState()) ||
      publication.getNextAttemptAt().isAfter(now)
    ) return;
    if (publication.getAttempts() >= properties.getMaxPublicationAttempts()) {
      publication.setState(PublicationState.FAILED);
      publication.setLastError(
        "Maximum Kafka publication attempts reached; request failed; retry after broker recovery"
      );
      return;
    }
    publication.setAttempts(publication.getAttempts() + 1);
    try {
      var received = inbound
        .findById(publication.getInboundMessageId())
        .orElseThrow();
      try (var traceScope = OutboxTrace.restore(publication.getTraceParent())) {
        publisher.publish(
          publication.getTopic(),
          publication.getPartitionKey(),
          publication.getPayload(),
          java.util.Map.of(
            "x-counterparty-id",
            received.getCounterpartyId(),
            "x-fix-session",
            received.getSessionKey()
          )
        );
        LOG.atDebug().addKeyValue("event_id", publication.getEventId()).log("Published outbox event");
      }
      publication.setState(PublicationState.PUBLISHED);
      publication.setPublishedAt(now);
      publication.setLastError(null);
    } catch (Exception e) {
      publication.setLastError(OutboundDeliveryService.safeError(e));
      publication.setNextAttemptAt(
        now.plusSeconds(properties.getRetrySeconds())
      );
      if (
        publication.getAttempts() >= properties.getMaxPublicationAttempts()
      ) publication.setState(PublicationState.FAILED);
    }
  }
}
