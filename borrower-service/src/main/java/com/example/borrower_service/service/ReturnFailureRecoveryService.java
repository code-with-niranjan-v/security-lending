package com.example.borrower_service.service;

import static com.example.borrower_service.repository.BorrowerRepository.*;

import com.example.borrower_service.dto.*;
import com.example.borrower_service.model.*;
import com.example.borrower_service.repository.BorrowerRepository;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Failed return assets are released only after the lender durably fences the original return. */
@Service
@RequiredArgsConstructor
@Transactional
public class ReturnFailureRecoveryService {

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private org.springframework.cloud.client.loadbalancer.LoadBalancerClient discovery;

  private final BorrowerRepository db;
  private final BorrowerEventProjectionService projections;
  private final LendingService lending;
  private final JsonMapper json = JsonMapper.builder().build();

  @Value("${recovery.lender-service-url:http://localhost:8084}")
  private String lenderUrl;

  @Value("${recovery.borrower-counterparty-id:BORROWER_DEMO}")
  private String counterpartyId;

  public boolean markFailed(long org, long id, String eventId, String reason) {
    db.lock(org);
    var row = db.owned(LoanReturn.class, org, id);
    if (!Set.of("PENDING", "FAILED").contains(row.get("status"))) return false;
    var request = db.find(LoanReturn.class, id);
    if (
      request.getFailureEventId() != null &&
      !request.getFailureEventId().equals(eventId)
    ) throw new IllegalArgumentException(
      "Return failure refers to another event"
    );
    request.setStatus("FAILED");
    request.setFailureEventId(eventId);
    request.setFailureReason(
      reason == null
        ? "Delivery retries exhausted"
        : reason.substring(0, Math.min(1000, reason.length()))
    );
    return true;
  }

  public String reconcile(OutboxEvent event) {
    var command = json.readTree(event.getPayload());
    long org = event.getOrganizationId(),
      id = command.path("referenceId").asLong();
    db.lock(org);
    var request = db.find(LoanReturn.class, id);
    if (
      !request.getOrganizationId().equals(org)
    ) throw new IllegalArgumentException("Return ownership mismatch");
    if (
      !"FAILED".equals(request.getStatus()) || request.isAssetsReleased()
    ) return request.getStatus();
    var factory =
      new org.springframework.http.client.SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(1500);
    factory.setReadTimeout(3000);
    String institution =
      counterpartyId == null || counterpartyId.isBlank()
        ? db.find(Organization.class, org).getRegistrationNumber()
        : counterpartyId;
    JsonNode response;
    try {
      response = RestClient.builder()
        .requestInterceptor((outboundRequest, outboundBody, execution) ->
          com.example.platform.DiscoveryHttp.execute(
            discovery,
            outboundRequest,
            outboundBody,
            execution
          )
        )
        .baseUrl(lenderUrl)
        .requestFactory(factory)
        .build()
        .post()
        .uri("/api/v1/transport/returns/cancel")
        .contentType(MediaType.APPLICATION_JSON)
        .header("x-counterparty-id", institution)
        .body(event.getPayload())
        .retrieve()
        .body(JsonNode.class);
    } catch (org.springframework.web.client.RestClientException unavailable) {
      // Keep securities and fee cash reserved until cancellation/settlement is authoritative.
      return "AWAITING_CANCELLATION";
    }
    var confirmed = response.has("data") ? response.get("data") : response;
    if (
      confirmed.path("organizationId").asLong() != org ||
      confirmed.path("referenceId").asLong() != id ||
      !Set.of("RETURN_REJECTED", "RETURN_SETTLED").contains(
        confirmed.path("type").asText()
      ) ||
      !confirmed
        .path("data")
        .path("recoveryEventId")
        .asText()
        .equals(request.getFailureEventId())
    ) throw new IllegalStateException(
      "Cancellation confirmation does not match the failed return"
    );
    var details = json.convertValue(
      confirmed.path("data"),
      new tools.jackson.core.type.TypeReference<Map<String, Object>>() {}
    );
    projections.apply(
      new BorrowerEvent(
        confirmed.path("eventId").asText(),
        confirmed.path("type").asText(),
        org,
        id,
        confirmed.path("version").asLong(),
        Instant.parse(confirmed.path("occurredAt").asText()),
        details
      )
    );
    return request.getStatus();
  }

  public long retry(long org, long id) {
    db.lock(org);
    var original = db.find(LoanReturn.class, id);
    if (
      !original.getOrganizationId().equals(org)
    ) throw new ResponseStatusException(
      HttpStatus.NOT_FOUND,
      "Return not found"
    );
    if (
      original.getReplacementReturnId() != null
    ) return original.getReplacementReturnId();
    if (
      !"FAILED".equals(original.getStatus()) || !original.isAssetsReleased()
    ) throw new ResponseStatusException(
      HttpStatus.CONFLICT,
      "Return cancellation is still being confirmed. Assets cannot be reused yet; refresh after lender-service is available."
    );
    var next = lending.requestReturn(
      org,
      original.getLoanId(),
      new ReturnRequest(original.getQuantity(), original.getRecallId())
    );
    long replacement = n(next, "id");
    original.setReplacementReturnId(replacement);
    return replacement;
  }
}
