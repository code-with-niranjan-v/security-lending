package com.example.borrower_service.service;

import com.example.borrower_service.model.*;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class DeliveryRecoveryService {

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private org.springframework.cloud.client.loadbalancer.LoadBalancerClient discovery;

  private final EntityManager em;
  private final Clock clock;

  @org.springframework.beans.factory.annotation.Autowired
  private ReturnFailureRecoveryService returnRecovery;

  private final JsonMapper json = JsonMapper.builder().build();

  @Value("${recovery.borrower-adapter-url:http://localhost:8082}")
  private String borrowerAdapter;

  @Value("${recovery.lender-adapter-url:http://localhost:8083}")
  private String lenderAdapter;

  @Value("${recovery.enabled:true}")
  private boolean enabled;

  private RestClient client(String source) {
    var factory =
      new org.springframework.http.client.SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(1500);
    factory.setReadTimeout(2500);
    return RestClient.builder()
      .requestInterceptor((outboundRequest, outboundBody, execution) ->
        com.example.platform.DiscoveryHttp.execute(
          discovery,
          outboundRequest,
          outboundBody,
          execution
        )
      )
      .baseUrl(source.equals("BORROWER_FIX") ? borrowerAdapter : lenderAdapter)
      .requestFactory(factory)
      .build();
  }

  @Transactional(readOnly = true)
  public List<DeliveryFailure> list(long org) {
    if (
      em.find(Organization.class, org) == null
    ) throw new ResponseStatusException(
      HttpStatus.NOT_FOUND,
      "Organization not found"
    );
    var listed = em
      .createQuery(
        "select f from DeliveryFailure f where f.organizationId=:org and f.status in ('FAILED','RETRYING') order by f.updatedAt desc",
        DeliveryFailure.class
      )
      .setParameter("org", org)
      .getResultList();
    for (var row : listed)
      if ("RETURN_REQUESTED".equals(row.getEventType())) {
        var returned = em.find(
          LoanReturn.class,
          Long.parseLong(row.getReferenceId())
        );
        row.setAssetsReleased(returned.isAssetsReleased());
        row.setCompensationStatus(
          returned.isAssetsReleased()
            ? "RELEASED"
            : "SETTLED".equals(returned.getStatus())
              ? "SETTLED"
              : "AWAITING_LENDER_CANCELLATION"
        );
        row.setReplacementReferenceId(returned.getReplacementReturnId());
      }
    return listed;
  }

  private JsonNode unwrap(JsonNode node) {
    return node.has("data") ? node.get("data") : node;
  }

  private JsonNode body(OutboxEvent event) {
    return json.readTree(event.getPayload());
  }

  private String eventId(OutboxEvent event) {
    return body(event).path("eventId").asText();
  }

  private void failed(
    OutboxEvent event,
    String source,
    String stage,
    long transportId,
    int attempts,
    String error
  ) {
    if (body(event).path("type").asText().equals("RETURN_REQUESTED")) {
      if (
        !returnRecovery.markFailed(
          event.getOrganizationId(),
          body(event).path("referenceId").asLong(),
          eventId(event),
          error
        )
      ) return;
    }
    String key = source + ":" + stage + ":" + transportId;
    var row = em.find(
      DeliveryFailure.class,
      key,
      LockModeType.PESSIMISTIC_WRITE
    );
    if (
      row != null &&
      Set.of("CLOSED", "FAILED", "REPLACED").contains(row.getStatus())
    ) return;
    boolean notify = row == null || !row.getStatus().equals("FAILED");
    boolean fresh = row == null;
    if (fresh) {
      row = new DeliveryFailure();
      row.setId(key);
    }
    row.setOrganizationId(event.getOrganizationId());
    row.setOutboxId(event.getId());
    row.setEventId(eventId(event));
    row.setEventType(body(event).path("type").asText("UNKNOWN"));
    row.setReferenceId(body(event).path("referenceId").asText());
    row.setStatus("FAILED");
    row.setSource(source);
    row.setStage(stage);
    row.setTransportId(transportId);
    row.setAttempts(attempts);
    row.setLastError(
      error == null ? null : error.substring(0, Math.min(1000, error.length()))
    );
    row.setUpdatedAt(clock.instant());
    if (fresh) em.persist(row);
    if (notify) {
      var note = new Notification();
      note.setOrganizationId(event.getOrganizationId());
      note.setType("REQUEST_DELIVERY_FAILED");
      note.setReference(key);
      note.setIsRead(false);
      note.setCreatedAt(clock.instant());
      String message =
        row.getEventType() +
        " #" +
        row.getReferenceId() +
        " delivery failed. Open Failed deliveries to retry or close.";
      note.setMessage(message.substring(0, Math.min(500, message.length())));
      em.persist(note);
    }
  }

  /** Runs without Kafka: a broker outage cannot suppress failure notifications. */
  @Scheduled(fixedDelayString = "${recovery.poll-ms:10000}")
  @Transactional
  public void scan() {
    if (!enabled) return;
    for (var event : em
      .createQuery(
        "select e from OutboxEvent e where e.status='FAILED'",
        OutboxEvent.class
      )
      .getResultList())
      failed(
        event,
        "OUTBOX",
        "kafka",
        event.getId(),
        event.getAttempts(),
        event.getLastError()
      );
    for (String source : List.of("BORROWER_FIX", "LENDER_FIX")) {
      try {
        // Read every page; a failed message for another tenant must not hide this tenant's failures.
        int page = 0,
          pages = 1;
        while (page < pages) {
          var response = unwrap(
            client(source)
              .get()
              .uri("/api/v1/adapter/failures?page=" + page + "&size=200")
              .retrieve()
              .body(JsonNode.class)
          );
          pages = Math.max(
            response.path("outbound").path("totalPages").asInt(1),
            response.path("publications").path("totalPages").asInt(1)
          );
          for (String stage : List.of("outbound", "publications"))
            for (var item : response.path(stage).path("content")) {
              String id = item.path("eventId").asText();
              // Match the immutable event to its originating local outbox. Never trust the peer's organization ID.
              var matches = em
                .createQuery(
                  "select e from OutboxEvent e where e.payload like :needle",
                  OutboxEvent.class
                )
                .setParameter("needle", "%" + id + "%")
                .getResultList();
              for (var event : matches)
                if (eventId(event).equals(id)) failed(
                  event,
                  source,
                  stage,
                  item.path("id").asLong(),
                  item.path("attempts").asInt(),
                  item.path("lastError").asText("Transport retries exhausted")
                );
            }
          page++;
        }
      } catch (org.springframework.web.client.RestClientException ignored) {
        /* Keep durable failures; try again next poll. */
      }
    }
    for (var failure : em
      .createQuery(
        "select f from DeliveryFailure f where f.eventType='RETURN_REQUESTED' and f.compensationComplete=false and f.status in ('FAILED','CLOSED','REPLACED')",
        DeliveryFailure.class
      )
      .getResultList()) {
      var event = em.find(OutboxEvent.class, failure.getOutboxId());
      returnRecovery.markFailed(
        failure.getOrganizationId(),
        Long.parseLong(failure.getReferenceId()),
        failure.getEventId(),
        failure.getLastError()
      );
      String business = returnRecovery.reconcile(event);
      var returned = em.find(
        LoanReturn.class,
        Long.parseLong(failure.getReferenceId())
      );
      if (
        returned.isAssetsReleased() ||
        "SETTLED".equals(business) ||
        "REJECTED".equals(business)
      ) {
        // The receiver fence makes any delayed original copy harmless. Stop retrying the old transport.
        event.setStatus("CLOSED");
        failure.setCompensationComplete(closeFencedTransport(failure));
      }
      if (returned.getReplacementReturnId() != null) failure.setStatus(
        "REPLACED"
      );
      else if (
        "SETTLED".equals(business) || "REJECTED".equals(business)
      ) failure.setStatus("RECOVERED");
    }
    // A late acknowledgement or a retry that succeeded must resolve the alert automatically.
    for (var row : em
      .createQuery(
        "select f from DeliveryFailure f where f.status in ('FAILED','RETRYING')",
        DeliveryFailure.class
      )
      .getResultList()) {
      em.refresh(row, LockModeType.PESSIMISTIC_WRITE);
      if (!Set.of("FAILED", "RETRYING").contains(row.getStatus())) continue;
      // A failed return remains visible until a replacement is requested or the user closes it.
      if ("RETURN_REQUESTED".equals(row.getEventType())) continue;
      try {
        String state;
        if (row.getSource().equals("OUTBOX")) state = em
          .find(OutboxEvent.class, row.getOutboxId())
          .getStatus();
        else state = unwrap(
          client(row.getSource())
            .get()
            .uri(
              "/api/v1/adapter/" + row.getStage() + "/" + row.getTransportId()
            )
            .retrieve()
            .body(JsonNode.class)
        )
          .path("state")
          .asText();
        if (Set.of("PUBLISHED", "ACKNOWLEDGED").contains(state)) row.setStatus(
          "RECOVERED"
        );
        else if (state.equals("CLOSED")) row.setStatus("CLOSED");
        else if (!Set.of("FAILED", "SUSPENDED").contains(state)) row.setStatus(
          "RETRYING"
        );
        row.setUpdatedAt(clock.instant());
      } catch (org.springframework.web.client.RestClientException ignored) {}
    }
  }

  private boolean closeFencedTransport(DeliveryFailure failure) {
    if (failure.getSource().equals("OUTBOX")) return true;
    try {
      var path =
        "/api/v1/adapter/" +
        failure.getStage() +
        "/" +
        failure.getTransportId();
      String state = unwrap(
        client(failure.getSource())
          .get()
          .uri(path)
          .retrieve()
          .body(JsonNode.class)
      )
        .path("state")
        .asText();
      if (
        Set.of("CLOSED", "ACKNOWLEDGED", "PUBLISHED").contains(state)
      ) return true;
      if (Set.of("FAILED", "SUSPENDED").contains(state)) {
        client(failure.getSource())
          .post()
          .uri(path + "/close")
          .retrieve()
          .toBodilessEntity();
        return true;
      }
    } catch (org.springframework.web.client.RestClientException ignored) {
      /* Retry after the adapter recovers. */
    }
    return false;
  }

  @Transactional
  public DeliveryFailure decide(long org, String id, boolean retry) {
    var row = em.find(
      DeliveryFailure.class,
      id,
      LockModeType.PESSIMISTIC_WRITE
    );
    if (
      row == null || !row.getOrganizationId().equals(org)
    ) throw new ResponseStatusException(
      HttpStatus.NOT_FOUND,
      "Failure not found"
    );
    if (!row.getStatus().equals("FAILED")) throw new ResponseStatusException(
      HttpStatus.CONFLICT,
      "Only failed deliveries can be retried or closed; refresh status"
    );
    boolean failedReturn = "RETURN_REQUESTED".equals(row.getEventType());
    if (failedReturn) {
      var returned = em.find(
        LoanReturn.class,
        Long.parseLong(row.getReferenceId())
      );
      if (
        !returned.isAssetsReleased() &&
        !"SETTLED".equals(returned.getStatus()) &&
        !"REJECTED".equals(returned.getStatus())
      ) throw new ResponseStatusException(
        HttpStatus.CONFLICT,
        "Awaiting lender cancellation confirmation; reservations cannot be released yet"
      );
    }
    // A compensated return must never resend its old event: assets have already been restored.
    // Close the original failed transport first, then reserve a new return atomically below.
    boolean transportRetry = retry && !failedReturn;
    if (!failedReturn && row.getSource().equals("OUTBOX")) {
      var event = em.find(
        OutboxEvent.class,
        row.getOutboxId(),
        LockModeType.PESSIMISTIC_WRITE
      );
      if (
        !event.getStatus().equals("FAILED")
      ) throw new ResponseStatusException(
        HttpStatus.CONFLICT,
        "Delivery status changed; refresh"
      );
      event.setStatus(transportRetry ? "PENDING" : "CLOSED");
      if (transportRetry) {
        event.setAttempts(0);
        event.setNextAttemptAt(clock.instant());
        event.setLastError(null);
      }
    } else if (!failedReturn) {
      try {
        client(row.getSource())
          .post()
          .uri(
            "/api/v1/adapter/" +
              row.getStage() +
              "/" +
              row.getTransportId() +
              (transportRetry ? "/retry" : "/close")
          )
          .retrieve()
          .toBodilessEntity();
      } catch (
        org.springframework.web.client.RestClientResponseException failure
      ) {
        throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Transport status changed or action was rejected. Refresh before retrying."
        );
      } catch (org.springframework.web.client.RestClientException failure) {
        throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY,
          "Adapter unavailable or outcome uncertain. Refresh before retrying."
        );
      }
    }
    if (failedReturn && retry) {
      long replacement = returnRecovery.retry(
        org,
        Long.parseLong(row.getReferenceId())
      );
      row.setReplacementReferenceId(replacement);
      row.setStatus("REPLACED");
    } else row.setStatus(retry ? "RETRYING" : "CLOSED");
    row.setUpdatedAt(clock.instant());
    return row;
  }
}
