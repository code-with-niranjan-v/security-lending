package com.example.fix_adapter_service.service;

import com.example.fix_adapter_service.config.AdapterProperties;
import com.example.fix_adapter_service.model.*;
import com.example.fix_adapter_service.observability.OutboxTrace;
import com.example.fix_adapter_service.repository.OutboundMessageRepository;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.StatusCode;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OutboundDeliveryService {
  private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(OutboundDeliveryService.class);

  private final OutboundMessageRepository messages;
  private final FixTransport transport;
  private final FixMessageCodec codec;
  private final AdapterProperties properties;
  private final Clock clock;

  @Transactional
  public void deliverNext(String sessionKey) {
    var pending = messages.lockHead(sessionKey, PageRequest.of(0, 1));
    if (pending.isEmpty()) return;
    var message = pending.get(0);
    var now = clock.instant();
    if (
      java.util.Set.of(
        DeliveryState.SUSPENDED,
        DeliveryState.FAILED,
        DeliveryState.CLOSED
      ).contains(message.getState()) ||
      message.getNextAttemptAt().isAfter(now)
    ) return;
    if (message.getAttempts() >= properties.getMaxDeliveryAttempts()) {
      message.setState(DeliveryState.FAILED);
      message.setLastError(
        "Maximum FIX delivery attempts reached; no durable peer receipt"
      );
      return;
    }
    message.setAttempts(message.getAttempts() + 1);
    if (!transport.loggedOn(sessionKey)) {
      message.setLastError("FIX session is not logged on");
      message.setNextAttemptAt(now.plusSeconds(properties.getRetrySeconds()));
      if (
        message.getAttempts() >= properties.getMaxDeliveryAttempts()
      ) message.setState(DeliveryState.FAILED);
      return;
    }
    try (var parent = OutboxTrace.restore(message.getTraceParent())) {
      var span = GlobalOpenTelemetry.getTracer("lending.fix").spanBuilder("FIX send")
          .setAttribute("lending.event_id", message.getEventId()).startSpan();
      try (var scope = span.makeCurrent()) {
        if (!transport.send(sessionKey, codec.business(message))) {
          throw new IllegalStateException("FIX send returned false");
        }
        LOG.atDebug().addKeyValue("event_id", message.getEventId()).log("Sent FIX event");
      } catch (Exception e) {
        span.setStatus(StatusCode.ERROR);
        throw e;
      } finally {
        span.end();
      }
      message.setState(DeliveryState.AWAITING_RECEIPT);
      message.setLastSentAt(now);
      message.setLastError(null);
      message.setNextAttemptAt(
        now.plusSeconds(properties.getReceiptTimeoutSeconds())
      );
    } catch (Exception e) {
      message.setLastError(safeError(e));
      message.setNextAttemptAt(now.plusSeconds(properties.getRetrySeconds()));
      if (
        message.getAttempts() >= properties.getMaxDeliveryAttempts()
      ) message.setState(DeliveryState.FAILED);
    }
  }

  static String safeError(Exception e) {
    String value =
      e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
    return value.substring(0, Math.min(1000, value.length()));
  }
}
